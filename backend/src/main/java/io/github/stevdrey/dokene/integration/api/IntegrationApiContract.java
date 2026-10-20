// ABOUTME: Frozen v1 HTTP request and response shapes for /api/integrations/whatsapp (ADR 0023 §3.3, ADR 0024 §1).
// ABOUTME: Mirrored by frontend/src/features/integrations/types and pinned by contracts/messaging/v1/integration.json.
package io.github.stevdrey.dokene.integration.api;

import io.github.stevdrey.dokene.http.StrictRequests;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transport shapes only; format and ownership rules stay in the domain and service. Responses never carry secret
 * values: {@code secretRef} is a bare key, not a credential.
 */
public final class IntegrationApiContract {
    private IntegrationApiContract() {
    }

    /** The shared strict reader (unknown members such as a raw token are refused), also used by the fixture tests. */
    public static JsonMapper strictReader() {
        return StrictRequests.reader();
    }

    /** Binds a request body, mapping every malformed shape to {@link InvalidRequestException}. */
    public static IntegrationRequest read(JsonNode body) {
        return StrictRequests.read(body, IntegrationRequest.class).orElseThrow(InvalidRequestException::new);
    }

    /**
     * Binds the kill switch body: an object with exactly one member {@code enabled} holding a JSON boolean. Checked on
     * the tree rather than through the strict reader, which would coerce a number or string standing in for the boolean.
     */
    public static OutboundRequest readOutbound(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("enabled").isBoolean()) {
            throw new InvalidRequestException();
        }
        return new OutboundRequest(body.get("enabled").booleanValue());
    }

    /** A request that is not a valid integration body or carries an invalid precondition header. */
    public static final class InvalidRequestException extends RuntimeException {
        public InvalidRequestException() {
            super("Request is invalid");
        }
    }

    /** {@code PUT /api/integrations/whatsapp}. Required members are enforced at construction. */
    public record IntegrationRequest(String phoneNumberId, String businessAccountId, String displayPhoneNumber,
            Boolean enabled, String secretRef) {
        public IntegrationRequest {
            Objects.requireNonNull(phoneNumberId, "phoneNumberId is required");
            Objects.requireNonNull(businessAccountId, "businessAccountId is required");
            Objects.requireNonNull(enabled, "enabled is required");
            Objects.requireNonNull(secretRef, "secretRef is required");
        }
    }

    /** {@code PUT /api/integrations/whatsapp/outbound} (spec 0008): the tenant outbound kill switch target value. */
    public record OutboundRequest(Boolean enabled) {
        public OutboundRequest {
            Objects.requireNonNull(enabled, "enabled is required");
        }
    }

    /** Returned by {@code GET} and {@code PUT}; every member is always emitted, null included. */
    public record IntegrationResponse(boolean enabled, boolean outboundEnabled, String phoneNumberId,
            String businessAccountId, String displayPhoneNumber, String secretRef, boolean webhookVerified,
            long version) {
    }
}
