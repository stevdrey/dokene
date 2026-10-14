package io.github.stevdrey.dokene.integration.persistence.jdbc;

import io.github.stevdrey.dokene.integration.application.IntegrationRefusedException;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWhatsAppIntegrationRepository implements WhatsAppIntegrationRepository {
    private static final String PROVIDER = "META_WHATSAPP";

    private final JdbcTemplate jdbc;

    public JdbcWhatsAppIntegrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WhatsAppIntegration> findByTenant(TenantId tenantId) {
        return jdbc.query("""
                SELECT * FROM dokene.tenant_messaging_integrations WHERE tenant_id = ? AND provider = ?
                """, (rs, row) -> new WhatsAppIntegration(rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)), rs.getString("phone_number_id"),
                rs.getString("business_account_id"), rs.getString("display_phone_number"), rs.getBoolean("enabled"),
                rs.getString("secret_ref"), rs.getLong("credential_revision"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getLong("version")), tenantId.value(), PROVIDER).stream().findFirst();
    }

    @Override
    public void insert(WhatsAppIntegration integration) {
        try {
            jdbc.update("""
                    INSERT INTO dokene.tenant_messaging_integrations (id, tenant_id, provider, phone_number_id,
                        business_account_id, display_phone_number, enabled, secret_ref, credential_revision,
                        created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, integration.id(), integration.tenantId().value(), PROVIDER, integration.phoneNumberId(),
                    integration.businessAccountId(), integration.displayPhoneNumber(), integration.enabled(),
                    integration.secretRef(), integration.credentialRevision(),
                    Timestamp.from(integration.createdAt()), Timestamp.from(integration.updatedAt()),
                    integration.version());
        } catch (DataIntegrityViolationException conflict) {
            // A mapping owned by another tenant is indistinguishable from any other invalid mapping.
            throw new IntegrationRefusedException(MessagingErrorCode.INVALID_INPUT);
        }
    }

    @Override
    public boolean update(WhatsAppIntegration integration, long expectedVersion) {
        try {
            return jdbc.update("""
                    UPDATE dokene.tenant_messaging_integrations
                    SET phone_number_id = ?, business_account_id = ?, display_phone_number = ?, enabled = ?,
                        secret_ref = ?, credential_revision = ?, updated_at = ?, version = version + 1
                    WHERE tenant_id = ? AND id = ? AND version = ?
                    """, integration.phoneNumberId(), integration.businessAccountId(),
                    integration.displayPhoneNumber(), integration.enabled(), integration.secretRef(),
                    integration.credentialRevision(), Timestamp.from(integration.updatedAt()),
                    integration.tenantId().value(), integration.id(), expectedVersion) == 1;
        } catch (DataIntegrityViolationException conflict) {
            throw new IntegrationRefusedException(MessagingErrorCode.INVALID_INPUT);
        }
    }
}
