package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Optional;

public interface WhatsAppIntegrationRepository {
    Optional<WhatsAppIntegration> findByTenant(TenantId tenantId);

    /** Throws {@link IntegrationRefusedException} (invalid input) when the phone number id is already mapped. */
    void insert(WhatsAppIntegration integration);

    /** Compare-and-set on version; returns false when the stored version differs. */
    boolean update(WhatsAppIntegration integration, long expectedVersion);
}
