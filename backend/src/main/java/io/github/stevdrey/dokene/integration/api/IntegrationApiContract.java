// ABOUTME: Frozen v1 HTTP request and response shapes for /api/integrations/whatsapp (ADR 0023 §3.3, ADR 0024 §1).
// ABOUTME: Mirrored by frontend/src/features/integrations/types and pinned by contracts/messaging/v1/integration.json.
package io.github.stevdrey.dokene.integration.api;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Transport shapes only; format and ownership rules stay in the domain and service. Responses never carry secret
 * values: {@code secretRef} is a bare key, not a credential.
 */
public final class IntegrationApiContract {
    private IntegrationApiContract() {
    }

    /** {@code PUT /api/integrations/whatsapp}. Required members are enforced at construction. */
    public record IntegrationRequest(String phoneNumberId, String businessAccountId, String displayPhoneNumber,
            Boolean enabled, String secretRef) {
        private static final Set<String> MEMBERS = Set.of("phoneNumberId", "businessAccountId", "displayPhoneNumber",
                "enabled", "secretRef");

        public IntegrationRequest {
            Objects.requireNonNull(phoneNumberId, "phoneNumberId is required");
            Objects.requireNonNull(businessAccountId, "businessAccountId is required");
            Objects.requireNonNull(enabled, "enabled is required");
            Objects.requireNonNull(secretRef, "secretRef is required");
        }

        /**
         * The runtime reader ignores unknown members, so the controller binds a map and this rejects unknown members
         * (for example a raw token) and wrongly typed values instead.
         */
        static IntegrationRequest from(Map<String, Object> body) {
            if (body == null || !MEMBERS.containsAll(body.keySet()) || !(body.get("enabled") instanceof Boolean)) {
                throw new IllegalArgumentException("Request is invalid");
            }
            try {
                return new IntegrationRequest(text(body, "phoneNumberId"), text(body, "businessAccountId"),
                        text(body, "displayPhoneNumber"), (Boolean) body.get("enabled"), text(body, "secretRef"));
            } catch (NullPointerException missing) {
                throw new IllegalArgumentException("Request is invalid");
            }
        }

        private static String text(Map<String, Object> body, String member) {
            Object value = body.get(member);
            if (value != null && !(value instanceof String)) {
                throw new IllegalArgumentException("Request is invalid");
            }
            return (String) value;
        }
    }

    /** Returned by {@code GET} and {@code PUT}; every member is always emitted, null included. */
    public record IntegrationResponse(boolean enabled, String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, String secretRef, long version) {
    }
}
