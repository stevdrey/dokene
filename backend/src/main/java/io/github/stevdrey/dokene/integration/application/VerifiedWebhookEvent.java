// ABOUTME: A provider delivery event that passed signature verification, schema validation and stored tenant resolution.
// ABOUTME: Reaches DeliveryStatusSink only through VerifiedWebhookEventHandler; raw provider JSON never leaves the adapter.
package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;

/**
 * Constructed by the webhook boundary after, in order: HMAC check on the raw bytes, schema validation, tenant lookup
 * by the stored {@code phoneNumberId} (never a tenant id from the payload) and dedup on (provider, providerEventId).
 */
public record VerifiedWebhookEvent(Provider provider, String providerEventId, TenantId tenantId,
        DeliveryStatusReport report) {
    public static final int MAX_EVENT_ID_LENGTH = 128;

    public VerifiedWebhookEvent {
        Objects.requireNonNull(provider, "Provider is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(report, "Delivery report is required");
        if (providerEventId == null || providerEventId.isBlank() || providerEventId.length() > MAX_EVENT_ID_LENGTH
                || providerEventId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Provider event id is malformed");
        }
    }

    public enum Provider { META_WHATSAPP }
}
