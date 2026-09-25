package io.github.stevdrey.dokene.ai.application;

public record AiTokenUsage(long inputTokens, long outputTokens) {
    public AiTokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token counts cannot be negative");
        }
    }
}
