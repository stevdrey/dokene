package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Optional;
import java.util.OptionalLong;

public interface WhatsAppIntegrationRepository {
    Optional<WhatsAppIntegration> findByTenant(TenantId tenantId);

    /** Throws {@link PhoneNumberMappingConflictException} when the phone number id is already mapped. */
    void insert(WhatsAppIntegration integration);

    /**
     * Compare-and-set on version; returns the stored new version, or empty when the stored version differs. Throws
     * {@link PhoneNumberMappingConflictException} when the new phone number id is already mapped.
     */
    OptionalLong update(WhatsAppIntegration integration, long expectedVersion);
}
