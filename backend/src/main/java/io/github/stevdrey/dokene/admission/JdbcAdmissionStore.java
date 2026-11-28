// ABOUTME: JDBC AdmissionStore (ADR 0040): transaction scoped advisory locks, counts over the admission rows and the
// ABOUTME: pause upsert. Admission work runs under a JDBC savepoint of the caller's transaction on its own connection.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Admitted;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class JdbcAdmissionStore implements AdmissionStore {
    private static final String ADVISORY_LOCK = "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    JdbcAdmissionStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC template is required");
        this.ownTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        // The pause is written after the provider answered, never inside a claim transaction.
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * The savepoint is a JDBC one: the tenant aware data source refuses raw SAVEPOINT SQL. PostgreSQL aborts a whole
     * transaction on any failed statement, so without it a failed budget statement would take the caller's work down.
     */
    @Override
    public AdmissionDecision underSavepoint(SavepointWork work) {
        return jdbc.execute((ConnectionCallback<AdmissionDecision>) connection -> {
            Savepoint savepoint = connection.setSavepoint();
            JdbcSession session = new JdbcSession(connection);
            AdmissionDecision decision;
            try {
                decision = work.run(session);
                if (decision instanceof Admitted) {
                    session.restoreLockWaits();
                    connection.releaseSavepoint(savepoint);
                    return decision;
                }
            } catch (SQLException | RuntimeException failure) {
                undo(connection, savepoint, failure);
                throw failure;
            }
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
            return decision;
        });
    }

    /** A rollback that fails says more about the connection than the failure that led to it, so it is the one raised. */
    private static void undo(Connection connection, Savepoint savepoint, Exception failure) throws SQLException {
        try {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException rollbackFailure) {
            rollbackFailure.addSuppressed(failure);
            throw rollbackFailure;
        }
    }

    /** A plain overwrite: a new signal moves the end of the pause, it never adds to it. */
    @Override
    public void pause(TenantId tenant, Instant until) {
        ownTransaction.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO dokene.tenant_send_pauses (tenant_id, paused_until) VALUES (?, ?)
                ON CONFLICT (tenant_id) DO UPDATE SET paused_until = EXCLUDED.paused_until
                """, tenant.value(), Timestamp.from(until)));
    }

    private static final class JdbcSession implements Session {
        private final Connection connection;
        private String callerLockTimeout;

        private JdbcSession(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void boundLockWaits(Duration timeout) throws SQLException {
            try (PreparedStatement read = connection.prepareStatement("SELECT current_setting('lock_timeout')");
                    ResultSet row = read.executeQuery()) {
                row.next();
                callerLockTimeout = row.getString(1);
            }
            setLockTimeout(timeout.toMillis() + "ms");
        }

        /** An admitted caller keeps its transaction, so its own lock_timeout must be what it was. */
        private void restoreLockWaits() throws SQLException {
            if (callerLockTimeout != null) {
                setLockTimeout(callerLockTimeout);
            }
        }

        private void setLockTimeout(String value) throws SQLException {
            try (PreparedStatement write = connection.prepareStatement(
                    "SELECT set_config('lock_timeout', ?, true)")) {
                write.setString(1, value);
                write.execute();
            }
        }

        @Override
        public void lockTenant(TenantId tenant, AdmissionKind kind) throws SQLException {
            String id = tenant.value().toString();
            try (PreparedStatement lock = connection.prepareStatement(ADVISORY_LOCK)) {
                lock.setString(1, id + ":ADMISSION_" + kind.name());
                lock.setString(2, id);
                lock.execute();
            }
        }

        @Override
        public void lockGlobalSends() throws SQLException {
            try (PreparedStatement lock = connection.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext('ADMISSION_GLOBAL_SEND'), 0)")) {
                lock.execute();
            }
        }

        @Override
        public Optional<Instant> pausedUntil(TenantId tenant) throws SQLException {
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT paused_until FROM dokene.tenant_send_pauses WHERE tenant_id = ?")) {
                read.setObject(1, tenant.value());
                try (ResultSet row = read.executeQuery()) {
                    return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
                }
            }
        }

        /** PostgreSQL has no DELETE with LIMIT, so the batch is chosen by a subquery. */
        @Override
        public int pruneTenant(TenantId tenant, AdmissionKind kind, Instant cutoff, int batch) throws SQLException {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM dokene.tenant_budget_admissions WHERE id IN (
                        SELECT id FROM dokene.tenant_budget_admissions
                        WHERE tenant_id = ? AND kind = ? AND admitted_at <= ?
                        ORDER BY admitted_at LIMIT ?)
                    """)) {
                delete.setObject(1, tenant.value());
                delete.setString(2, kind.name());
                delete.setTimestamp(3, Timestamp.from(cutoff));
                delete.setInt(4, batch);
                return delete.executeUpdate();
            }
        }

        @Override
        public int prunePlatform(Instant cutoff, int batch) throws SQLException {
            try (PreparedStatement delete = connection.prepareStatement("""
                    DELETE FROM dokene.platform_send_admissions WHERE id IN (
                        SELECT id FROM dokene.platform_send_admissions
                        WHERE admitted_at <= ? ORDER BY admitted_at LIMIT ?)
                    """)) {
                delete.setTimestamp(1, Timestamp.from(cutoff));
                delete.setInt(2, batch);
                return delete.executeUpdate();
            }
        }

        @Override
        public WindowUsage tenantUsage(TenantId tenant, AdmissionKind kind, Instant windowStart, int limit)
                throws SQLException {
            String window = "FROM dokene.tenant_budget_admissions WHERE tenant_id = ? AND kind = ? AND admitted_at > ?";
            return usage(window, limit, statement -> {
                statement.setObject(1, tenant.value());
                statement.setString(2, kind.name());
                statement.setTimestamp(3, Timestamp.from(windowStart));
                return 4;
            });
        }

        @Override
        public WindowUsage platformUsage(Instant windowStart, int limit) throws SQLException {
            return usage("FROM dokene.platform_send_admissions WHERE admitted_at > ?", limit, statement -> {
                statement.setTimestamp(1, Timestamp.from(windowStart));
                return 2;
            });
        }

        /** The caller holds the lock that every writer of this window takes, so the two reads agree. */
        private WindowUsage usage(String window, int limit, WindowBinder binder) throws SQLException {
            int count;
            try (PreparedStatement read = connection.prepareStatement("SELECT count(*) " + window)) {
                binder.bind(read);
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    count = row.getInt(1);
                }
            }
            if (count < limit) {
                return new WindowUsage(count, null);
            }
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT admitted_at " + window + " ORDER BY admitted_at, id OFFSET ? LIMIT 1")) {
                read.setInt(binder.bind(read), count - limit);
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    return new WindowUsage(count, row.getTimestamp(1).toInstant());
                }
            }
        }

        @Override
        public void insertTenant(TenantId tenant, AdmissionKind kind, Instant admittedAt) throws SQLException {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO dokene.tenant_budget_admissions (tenant_id, kind, admitted_at) VALUES (?, ?, ?)")) {
                insert.setObject(1, tenant.value());
                insert.setString(2, kind.name());
                insert.setTimestamp(3, Timestamp.from(admittedAt));
                insert.executeUpdate();
            }
        }

        @Override
        public void insertPlatform(Instant admittedAt) throws SQLException {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO dokene.platform_send_admissions (admitted_at) VALUES (?)")) {
                insert.setTimestamp(1, Timestamp.from(admittedAt));
                insert.executeUpdate();
            }
        }
    }

    /** Binds the window's parameters and answers the index of the next free one. */
    @FunctionalInterface
    private interface WindowBinder {
        int bind(PreparedStatement statement) throws SQLException;
    }
}
