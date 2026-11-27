// ABOUTME: JDBC store of the provider_health row (ADR 0038). Each write is its own short transaction: lock the row,
// ABOUTME: take the database clock, apply the pure policy, update. A write that cannot run is counted, never raised.
package io.github.stevdrey.dokene.messaging.persistence.jdbc;

import io.github.stevdrey.dokene.messaging.application.ProviderHealthReader;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthRecorder;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthTelemetry;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthTelemetry.StoreOperation;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealth;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthEvent;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthEvent.SendSignal;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcProviderHealthStore implements ProviderHealthRecorder, ProviderHealthReader {
    private static final Logger log = LoggerFactory.getLogger(JdbcProviderHealthStore.class);
    /** The time comes from the database in the same statement, so instances with clock skew still agree. */
    private static final String SELECT_ROW = "SELECT h.*, now() AS db_now FROM dokene.provider_health h "
            + "WHERE h.provider = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;
    private final ProviderHealthPolicy policy;
    private final ProviderHealthTelemetry telemetry;

    public JdbcProviderHealthStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            ProviderHealthPolicy policy, ProviderHealthTelemetry telemetry) {
        this.jdbc = Objects.requireNonNull(jdbc, "JDBC template is required");
        this.ownTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        // Never part of a send transaction: the health row is not locked while any send lock is held.
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.policy = Objects.requireNonNull(policy, "Policy is required");
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
    }

    private record Read(ProviderHealth row, Instant now) {
    }

    private record Change(ProviderHealthState from, ProviderHealthState to) {
    }

    @Override
    public void record(MessagingProviderKey provider, ProviderSignalKind kind) {
        ProviderHealthEvent event = new SendSignal(kind);
        Change change;
        try {
            change = ownTransaction.execute(status -> apply(provider, event));
        } catch (DataAccessException | TransactionException failure) {
            telemetry.storeFailure(provider, StoreOperation.RECORD);
            log.warn("Provider health signal not recorded; provider={}, kind={}, errorType={}", provider, kind,
                    failure.getClass().getSimpleName());
            return;
        }
        telemetry.signal(provider, kind);
        if (change.from() != change.to()) {
            telemetry.transition(provider, change.from(), change.to());
            log.info("Provider health changed; provider={}, from={}, to={}, event={}", provider, change.from(),
                    change.to(), event.label());
        }
    }

    @Override
    public ProviderHealthStatus read(MessagingProviderKey provider) {
        try {
            Read read = jdbc.queryForObject(SELECT_ROW, (row, index) -> map(row), provider.name());
            return new ProviderHealthStatus(policy.effectiveState(read.row(), read.now()),
                    read.row().stateChangedAt(), Optional.ofNullable(read.row().lastSignalAt()));
        } catch (DataAccessException failure) {
            telemetry.storeFailure(provider, StoreOperation.READ);
            log.warn("Provider health not readable; provider={}, errorType={}", provider,
                    failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private Change apply(MessagingProviderKey provider, ProviderHealthEvent event) {
        // A busy row is a record failure after a second, not a request stuck behind another writer.
        jdbc.execute("SET LOCAL lock_timeout = '1s'");
        Read read = jdbc.queryForObject(SELECT_ROW + " FOR UPDATE", (row, index) -> map(row), provider.name());
        ProviderHealth next = policy.apply(read.row(), event, read.now());
        jdbc.update("""
                UPDATE dokene.provider_health
                SET state = ?, state_changed_at = ?, last_signal_at = ?, last_signal_kind = ?,
                    consecutive_outage_signals = ?, first_outage_signal_at = ?, consecutive_successes = ?,
                    cool_down_until = ?, cool_down_step = ?, probe_lease_until = ?
                WHERE provider = ?
                """, next.state().name(), timestamp(next.stateChangedAt()), timestamp(next.lastSignalAt()),
                next.lastSignalKind() == null ? null : next.lastSignalKind().name(), next.consecutiveOutageSignals(),
                timestamp(next.firstOutageSignalAt()), next.consecutiveSuccesses(), timestamp(next.coolDownUntil()),
                next.coolDownStep(), timestamp(next.probeLeaseUntil()), provider.name());
        return new Change(read.row().state(), next.state());
    }

    private static Read map(ResultSet row) throws SQLException {
        String kind = row.getString("last_signal_kind");
        return new Read(new ProviderHealth(MessagingProviderKey.valueOf(row.getString("provider")),
                ProviderHealthState.valueOf(row.getString("state")), instant(row, "state_changed_at"),
                instant(row, "last_signal_at"), kind == null ? null : ProviderSignalKind.valueOf(kind),
                row.getInt("consecutive_outage_signals"), instant(row, "first_outage_signal_at"),
                row.getInt("consecutive_successes"), instant(row, "cool_down_until"), row.getInt("cool_down_step"),
                instant(row, "probe_lease_until")), instant(row, "db_now"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
