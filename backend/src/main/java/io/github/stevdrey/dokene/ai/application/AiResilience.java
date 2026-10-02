package io.github.stevdrey.dokene.ai.application;

import java.util.Objects;

/** Wraps a raw {@link AiProvider} with the configured retry policy and telemetry. */
public final class AiResilience {
    private final AiRetryProperties retry;
    private final AiTelemetry telemetry;

    public AiResilience(AiRetryProperties retry, AiTelemetry telemetry) {
        this.retry = Objects.requireNonNull(retry, "Retry properties are required");
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
    }

    public AiProvider wrap(AiProvider provider) {
        return provider instanceof ResilientAiProvider ? provider : new ResilientAiProvider(provider, retry, telemetry);
    }
}
