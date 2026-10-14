// ABOUTME: Frozen v1 HTTP request and response shapes for /api/templates (ADR 0023 §3.2, ADR 0025).
// ABOUTME: Mirrored by frontend/src/features/integrations/types and pinned by contracts/messaging/v1/templates.json.
package io.github.stevdrey.dokene.template.api;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.template.application.TemplateErrorCode;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import java.time.Instant;
import java.util.List;
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
 * Transport shapes only. A request names the template by semantic intent in the path and by provider template name in
 * the body; there is no member that could carry a provider template id, and unknown members are refused.
 */
public final class TemplateApiContract {
    public static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    private static final JsonMapper STRICT_READER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    private TemplateApiContract() {
    }

    public static JsonMapper strictReader() {
        return STRICT_READER;
    }

    /** Binds a request body, mapping every malformed shape to {@link InvalidRequestException}. */
    public static TemplateRequest read(JsonNode body) {
        try {
            TemplateRequest request = body == null ? null : STRICT_READER.treeToValue(body, TemplateRequest.class);
            if (request == null) {
                throw new InvalidRequestException();
            }
            return request;
        } catch (JacksonException malformed) {
            throw new InvalidRequestException();
        }
    }

    public static final class InvalidRequestException extends RuntimeException {
        public InvalidRequestException() {
            super("Request is invalid");
        }
    }

    /** {@code PUT /api/templates/{templateIntent}}. Approval state is never part of a request. */
    public record TemplateRequest(String providerTemplateName, String language, List<String> parameterNames,
            Boolean enabled) {
        public TemplateRequest {
            Objects.requireNonNull(providerTemplateName, "providerTemplateName is required");
            Objects.requireNonNull(language, "language is required");
            Objects.requireNonNull(parameterNames, "parameterNames is required");
            Objects.requireNonNull(enabled, "enabled is required");
            parameterNames = List.copyOf(parameterNames);
        }
    }

    /**
     * One catalogue slot per intent. While {@code mapped} is false every other member is its neutral value:
     * {@code enabled} and {@code sendable} false, {@code approvalStatus} UNVERIFIED, null name, language, check time and
     * version, and no parameters. Every member is always emitted.
     */
    public record TemplateResponse(SemanticTemplateIntent templateIntent, boolean mapped, boolean enabled,
            String providerTemplateName, String language, List<String> parameterNames,
            TemplateApprovalStatus approvalStatus, Instant approvalCheckedAt, boolean sendable, Long version) {
    }

    public record TemplateListResponse(List<TemplateResponse> items) {
    }

    /** RFC 9457 body with exactly these members; same JSON as the messaging problem for the shared codes. */
    public record TemplateProblem(String type, String title, int status, TemplateErrorCode code) {
        public static final String TYPE = "about:blank";

        public static TemplateProblem of(TemplateErrorCode code) {
            return new TemplateProblem(TYPE, titleOf(code.status()), code.status(), code);
        }

        private static String titleOf(int status) {
            return switch (status) {
                case 400 -> "Bad Request";
                case 403 -> "Forbidden";
                case 404 -> "Not Found";
                case 409 -> "Conflict";
                case 503 -> "Service Unavailable";
                default -> throw new IllegalArgumentException("Unexpected problem status " + status);
            };
        }
    }
}
