// ABOUTME: Representation of the tenant integration (spec 0005 Feature design): the reference string, never a value.
// ABOUTME: The default representation has version 0 and no updatedAt; the ETag is the quoted version.
package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.integration.config.TenantMessagingIntegration;
import java.time.Instant;

public record IntegrationResponse(String provider, String phoneNumberId, String businessAccountId,
        String displayPhoneNumber, String secretRef, boolean enabled, boolean outboundEnabled,
        boolean webhookVerified, long version, Instant updatedAt) {

    static IntegrationResponse from(TenantMessagingIntegration row) {
        return new IntegrationResponse(TenantMessagingIntegration.PROVIDER, row.phoneNumberId(),
                row.businessAccountId(), row.displayPhoneNumber(),
                row.secretRef() == null ? null : row.secretRef().value(), row.enabled(), row.outboundEnabled(),
                row.webhookVerified(), row.version(), row.updatedAt());
    }
}
