package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Optional;
import java.util.OptionalLong;

public interface WhatsAppIntegrationRepository {
    Optional<WhatsAppIntegration> findByTenant(TenantId tenantId);

    /**
     * Stores a new, always unverified mapping. Unverified mappings of the same phone number id may coexist across
     * tenants (issue 210), so this never conflicts on the number.
     */
    void insert(WhatsAppIntegration integration);

    /**
     * Compare-and-set on version, including the ownership proof; returns the stored new version, or empty when the
     * stored version differs. Throws {@link PhoneNumberMappingConflictException} when the row would become verified for
     * a phone number id another tenant has already verified.
     */
    OptionalLong update(WhatsAppIntegration integration, long expectedVersion);
}
