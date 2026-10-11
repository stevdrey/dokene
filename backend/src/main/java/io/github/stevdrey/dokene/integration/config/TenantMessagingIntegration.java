// ABOUTME: The per tenant WhatsApp integration row (spec 0005): identifiers, a secret reference, two switches.
// ABOUTME: Version 0 is the default representation of a tenant without a row; it is never stored.
package io.github.stevdrey.dokene.integration.config;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TenantMessagingIntegration(UUID tenantId, String phoneNumberId, String businessAccountId,
        String displayPhoneNumber, SecretRef secretRef, boolean enabled, boolean outboundEnabled,
        boolean webhookVerified, long version, Instant createdAt, Instant updatedAt) {
    public static final String PROVIDER = "WHATSAPP";

    public TenantMessagingIntegration {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        if (version < 0) {
            throw new IllegalArgumentException("Version cannot be negative");
        }
        boolean stored = version > 0;
        if (stored != (createdAt != null) || stored != (updatedAt != null)) {
            throw new IllegalArgumentException("A stored row carries both timestamps; the default carries none");
        }
    }

    /** What a tenant without a row reads: every identifier null, every flag false, version 0. */
    public static TenantMessagingIntegration absent(UUID tenantId) {
        return new TenantMessagingIntegration(tenantId, null, null, null, null, false, false, false, 0, null, null);
    }

    public boolean stored() {
        return version > 0;
    }
}
