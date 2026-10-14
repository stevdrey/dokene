// ABOUTME: Frozen v1 HTTP request and response shapes for /api/integrations/whatsapp (ADR 0023 §3.3, ADR 0024 §1).
// ABOUTME: Mirrored by frontend/src/features/integrations/types and pinned by contracts/messaging/v1/integration.json.
package io.github.stevdrey.dokene.integration.api;

import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/**
 * Transport shapes only; format and ownership rules stay in the domain and service. Responses never carry secret
 * values: {@code secretRef} is a bare key, not a credential.
 */
public final class IntegrationApiContract {
    /**
     * The one reader for request bodies, used by the controller and by the fixture tests: unknown members (for
     * example a raw token) and wrongly typed scalars are rejected instead of ignored or coerced.
     */
    private static final JsonMapper STRICT_READER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // ALLOW_COERCION_OF_SCALARS leaves number/boolean to String coercion on: refuse it explicitly.
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private IntegrationApiContract() {
    }

    public static JsonMapper strictReader() {
        return STRICT_READER;
    }

    /** Binds a request body, mapping every malformed shape to {@link InvalidRequestException}. */
    public static IntegrationRequest read(JsonNode body) {
        try {
            IntegrationRequest request = body == null ? null : STRICT_READER.treeToValue(body, IntegrationRequest.class);
            if (request == null) {
                throw new InvalidRequestException();
            }
            return request;
        } catch (JacksonException malformed) {
            throw new InvalidRequestException();
        }
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

    /** Returned by {@code GET} and {@code PUT}; every member is always emitted, null included. */
    public record IntegrationResponse(boolean enabled, boolean outboundEnabled, String phoneNumberId,
            String businessAccountId, String displayPhoneNumber, String secretRef, boolean webhookVerified,
            long version) {
    }
}
