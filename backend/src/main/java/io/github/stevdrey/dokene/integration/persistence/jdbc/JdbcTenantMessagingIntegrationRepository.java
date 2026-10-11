// ABOUTME: JDBC adapter for the integration row; relies on RLS for the tenant and on the indexes for uniqueness.
// ABOUTME: Maps the two unique indexes and the primary key to closed codes by constraint name, never logging the driver message.
package io.github.stevdrey.dokene.integration.persistence.jdbc;

import io.github.stevdrey.dokene.integration.config.IntegrationErrorCode;
import io.github.stevdrey.dokene.integration.config.IntegrationRefusedException;
import io.github.stevdrey.dokene.integration.config.SecretRef;
import io.github.stevdrey.dokene.integration.config.TenantMessagingIntegration;
import io.github.stevdrey.dokene.integration.config.TenantMessagingIntegrationRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantMessagingIntegrationRepository implements TenantMessagingIntegrationRepository {
    static final String PHONE_NUMBER_ID_INDEX = "ux_tmi_phone_number_id";
    static final String SECRET_REF_INDEX = "ux_tmi_secret_ref";
    static final String PRIMARY_KEY = "tenant_messaging_integrations_pkey";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String COLUMNS = """
            tenant_id, phone_number_id, business_account_id, display_phone_number, secret_ref, enabled,
            outbound_enabled, webhook_verified, version, created_at, updated_at
            """;

    private final JdbcTemplate jdbc;

    public JdbcTenantMessagingIntegrationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "JdbcTemplate is required");
    }

    @Override
    public Optional<TenantMessagingIntegration> findByTenant(UUID tenantId) {
        return first(jdbc.query("SELECT " + COLUMNS + " FROM dokene.tenant_messaging_integrations WHERE tenant_id = ?",
                this::map, tenantId));
    }

    @Override
    public Optional<TenantMessagingIntegration> lockByTenant(UUID tenantId) {
        return first(jdbc.query("SELECT " + COLUMNS
                + " FROM dokene.tenant_messaging_integrations WHERE tenant_id = ? FOR UPDATE", this::map, tenantId));
    }

    @Override
    public TenantMessagingIntegration insert(TenantMessagingIntegration row) {
        if (row.version() != 1) {
            throw new IllegalArgumentException("A new integration row starts at version 1");
        }
        try {
            jdbc.update("""
                    INSERT INTO dokene.tenant_messaging_integrations (provider, """ + COLUMNS + """
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, TenantMessagingIntegration.PROVIDER, row.tenantId(), row.phoneNumberId(),
                    row.businessAccountId(), row.displayPhoneNumber(), secretRef(row.secretRef()), row.enabled(),
                    row.outboundEnabled(), row.webhookVerified(), row.version(), Timestamp.from(row.createdAt()),
                    Timestamp.from(row.updatedAt()));
        } catch (DataIntegrityViolationException exception) {
            throw refusal(exception);
        }
        return row;
    }

    @Override
    public TenantMessagingIntegration update(TenantMessagingIntegration expected, TenantMessagingIntegration next) {
        if (!expected.tenantId().equals(next.tenantId()) || next.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Update must target the same row and advance the version by one");
        }
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE dokene.tenant_messaging_integrations
                    SET phone_number_id = ?, business_account_id = ?, display_phone_number = ?, secret_ref = ?,
                        enabled = ?, outbound_enabled = ?, webhook_verified = ?, version = ?, updated_at = ?
                    WHERE tenant_id = ? AND version = ?
                    """, next.phoneNumberId(), next.businessAccountId(), next.displayPhoneNumber(),
                    secretRef(next.secretRef()), next.enabled(), next.outboundEnabled(), next.webhookVerified(),
                    next.version(), Timestamp.from(next.updatedAt()), expected.tenantId(), expected.version());
        } catch (DataIntegrityViolationException exception) {
            throw refusal(exception);
        }
        if (updated != 1) {
            throw new IntegrationRefusedException(IntegrationErrorCode.STALE_VERSION);
        }
        return next;
    }

    /**
     * The driver message names the constraint and the conflicting value, so only the constraint name is read and
     * the original exception is never chained into the refusal.
     */
    private static RuntimeException refusal(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                String message = sql.getMessage() == null ? "" : sql.getMessage();
                if (message.contains(PHONE_NUMBER_ID_INDEX)) {
                    return new IntegrationRefusedException(IntegrationErrorCode.PHONE_NUMBER_ID_IN_USE);
                }
                if (message.contains(SECRET_REF_INDEX)) {
                    return new IntegrationRefusedException(IntegrationErrorCode.SECRET_REF_IN_USE);
                }
                if (message.contains(PRIMARY_KEY)) {
                    return new IntegrationRefusedException(IntegrationErrorCode.STALE_VERSION);
                }
                return exception;
            }
            cause = cause.getCause();
        }
        return exception;
    }

    private TenantMessagingIntegration map(ResultSet row, int index) throws SQLException {
        String secretRef = row.getString("secret_ref");
        return new TenantMessagingIntegration(row.getObject("tenant_id", UUID.class), row.getString("phone_number_id"),
                row.getString("business_account_id"), row.getString("display_phone_number"),
                secretRef == null ? null : SecretRef.parse(secretRef), row.getBoolean("enabled"),
                row.getBoolean("outbound_enabled"), row.getBoolean("webhook_verified"), row.getLong("version"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private static String secretRef(SecretRef ref) {
        return ref == null ? null : ref.value();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
}
