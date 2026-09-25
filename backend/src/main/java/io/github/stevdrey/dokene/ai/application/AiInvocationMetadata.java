package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Diagnostic data only: never contains prompts, responses, credentials or customer content. */
public record AiInvocationMetadata(String providerId, String modelId, String providerRequestId,
        Duration latency, AiTokenUsage usage, AiCompletionStatus status) {
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:/-]{1,128}");

    public AiInvocationMetadata {
        providerId = safeId(providerId, "Provider identifier", true);
        modelId = safeId(modelId, "Model identifier", false);
        providerRequestId = safeId(providerRequestId, "Provider request identifier", false);
        Objects.requireNonNull(latency, "Latency is required");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("Latency cannot be negative");
        }
        Objects.requireNonNull(status, "Completion status is required");
    }

    private static String safeId(String value, String name, boolean required) {
        if (value == null && !required) {
            return null;
        }
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a safe identifier");
        }
        return value;
    }
}
