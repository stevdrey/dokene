package io.github.stevdrey.dokene.tenant.persistence.jpa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Flyway callback that provisions the active tenant-context signing key into the migration-owned
 * key store ({@code dokene.tenant_context_signing_keys}) using parameterized JDBC binding, and retires
 * every other key once its grace period has passed (issue 324, audit L6).
 *
 * <p>On every run, in one transaction: the configured key becomes the only key without a retirement
 * deadline; every other {@code ACTIVE} key gets {@code retire_after = now() + grace} (never later than a
 * deadline it already has, so re-running the job cannot extend a grace); keys past their deadline are marked
 * {@code RETIRED}. The database verifiers enforce {@code retire_after} on the database clock themselves (V39),
 * so a superseded key stops verifying when the grace ends even if the job never runs again. A retired key id
 * is never reactivated: rolling back to it after its grace fails the job instead, and its key material is
 * overwritten with random bytes when it is retired. A key id never changes its key: configuring different
 * material under an existing id fails the job, because that would revoke the old material with no grace.
 */
@Component
public class TenantContextSigningKeyCallback implements Callback {

    /** Longest accepted grace: a superseded key must not stay usable for an unbounded time. */
    static final Duration MAX_RETIREMENT_GRACE = Duration.ofDays(7);

    private final String keyId;
    private final byte[] signingKey;
    private final Duration retirementGrace;

    @Autowired
    public TenantContextSigningKeyCallback(
            @Value("${dokene.tenant-context.signing-key}") String signingKey,
            @Value("${dokene.tenant-context.key-id:default}") String keyId,
            @Value("${dokene.tenant-context.retirement-grace:1h}") Duration retirementGrace
    ) {
        this.signingKey = parseSigningKey(signingKey);
        this.keyId = parseKeyId(keyId);
        this.retirementGrace = parseRetirementGrace(retirementGrace);
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (event != Event.AFTER_MIGRATE) {
            return;
        }

        Connection connection = context.getConnection();
        try {
            boolean ownTransaction = connection.getAutoCommit();
            if (ownTransaction) {
                connection.setAutoCommit(false);
            }
            Throwable failure = null;
            try {
                provisionAndRetire(connection);
                if (ownTransaction) {
                    connection.commit();
                }
            } catch (SQLException | RuntimeException exception) {
                failure = exception;
                if (ownTransaction) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        exception.addSuppressed(sanitized(rollbackFailure));
                    }
                }
                throw exception;
            } finally {
                if (ownTransaction) {
                    try {
                        connection.setAutoCommit(true);
                    } catch (SQLException restoreFailure) {
                        if (failure == null) {
                            throw restoreFailure;
                        }
                        failure.addSuppressed(sanitized(restoreFailure));
                    }
                }
            }
        } catch (SQLException exception) {
            // PostgreSQL error details can quote a failing row, signing key included, so neither the driver
            // message nor its detail is kept: only the SQLState and the vendor code reach the caller and the logs.
            IllegalStateException failure = new IllegalStateException(
                    "Failed to provision tenant context signing key into dokene.tenant_context_signing_keys (SQLState %s)"
                            .formatted(exception.getSQLState()),
                    sanitized(exception)
            );
            for (Throwable suppressed : exception.getSuppressed()) {
                failure.addSuppressed(suppressed);
            }
            throw failure;
        }
    }

    /** A copy of a driver exception without its message, detail, cause or suppressed exceptions. */
    static SQLException sanitized(SQLException exception) {
        SQLException copy = new SQLException(
                "SQL failure (message withheld)", exception.getSQLState(), exception.getErrorCode());
        copy.setStackTrace(exception.getStackTrace());
        return copy;
    }

    private void provisionAndRetire(Connection connection) throws SQLException {
        try (PreparedStatement lock = connection.prepareStatement(
                "LOCK TABLE dokene.tenant_context_signing_keys IN SHARE ROW EXCLUSIVE MODE")) {
            // Serializes concurrent job runs, so two of them never interleave their schedules.
            lock.execute();
        }

        try (PreparedStatement retired = connection.prepareStatement("""
                SELECT 1 FROM dokene.tenant_context_signing_keys
                WHERE key_id = ?
                    AND (status = 'RETIRED' OR retire_after <= statement_timestamp())
                """)) {
            retired.setString(1, keyId);
            try (ResultSet row = retired.executeQuery()) {
                if (row.next()) {
                    throw new IllegalStateException(("Tenant context signing key id '%s' is retired and cannot be "
                            + "reactivated; configure a new DOKENE_TENANT_CONTEXT_KEY_ID with a new key").formatted(keyId));
                }
            }
        }

        try (PreparedStatement sameKey = connection.prepareStatement(
                "SELECT signing_key = ? FROM dokene.tenant_context_signing_keys WHERE key_id = ?")) {
            // Compares in the database and reads back only a boolean, never key material.
            sameKey.setBytes(1, signingKey);
            sameKey.setString(2, keyId);
            try (ResultSet row = sameKey.executeQuery()) {
                if (row.next() && !row.getBoolean(1)) {
                    throw new IllegalStateException(("Tenant context signing key id '%s' already holds a different key; "
                            + "configure a new DOKENE_TENANT_CONTEXT_KEY_ID for a new key").formatted(keyId));
                }
            }
        }

        try (PreparedStatement upsert = connection.prepareStatement("""
                INSERT INTO dokene.tenant_context_signing_keys (key_id, signing_key, status)
                VALUES (?, ?, 'ACTIVE')
                ON CONFLICT (key_id) DO UPDATE
                SET status = 'ACTIVE',
                    retire_after = NULL,
                    retired_at = NULL
                """)) {
            upsert.setString(1, keyId);
            upsert.setBytes(2, signingKey);
            upsert.executeUpdate();
        }

        try (PreparedStatement schedule = connection.prepareStatement("""
                UPDATE dokene.tenant_context_signing_keys
                SET retire_after = LEAST(
                        COALESCE(retire_after, 'infinity'::timestamptz),
                        statement_timestamp() + make_interval(secs => ?))
                WHERE key_id <> ? AND status = 'ACTIVE'
                """)) {
            schedule.setDouble(1, retirementGrace.toMillis() / 1000.0);
            schedule.setString(2, keyId);
            schedule.executeUpdate();
        }

        try (PreparedStatement retire = connection.prepareStatement("""
                UPDATE dokene.tenant_context_signing_keys
                SET status = 'RETIRED',
                    retired_at = retire_after,
                    signing_key = dokene.gen_random_bytes(32)
                WHERE status = 'ACTIVE' AND retire_after <= statement_timestamp()
                """)) {
            retire.executeUpdate();
        }
    }

    @Override
    public String getCallbackName() {
        return "TenantContextSigningKeyCallback";
    }

    private static byte[] parseSigningKey(String rawSigningKey) {
        if (rawSigningKey == null || !rawSigningKey.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "DOKENE_TENANT_CONTEXT_SIGNING_KEY must be a 32-byte hexadecimal value"
            );
        }
        return HexFormat.of().parseHex(rawSigningKey);
    }

    private static Duration parseRetirementGrace(Duration grace) {
        if (grace == null || grace.isNegative() || grace.compareTo(MAX_RETIREMENT_GRACE) > 0) {
            throw new IllegalArgumentException(
                    "DOKENE_TENANT_CONTEXT_KEY_RETIREMENT_GRACE must be between 0s and " + MAX_RETIREMENT_GRACE.toDays() + "d"
            );
        }
        return grace;
    }

    private static String parseKeyId(String rawKeyId) {
        if (rawKeyId == null || !rawKeyId.matches("^[0-9a-zA-Z_-]{1,32}$")) {
            throw new IllegalArgumentException(
                    "DOKENE_TENANT_CONTEXT_KEY_ID must be 1 to 32 characters matching [0-9a-zA-Z_-]"
            );
        }
        return rawKeyId;
    }
}
