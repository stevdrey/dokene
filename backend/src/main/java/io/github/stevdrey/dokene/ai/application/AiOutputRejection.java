package io.github.stevdrey.dokene.ai.application;

/**
 * Closed, content-free reason why an adapter rejected a structurally parseable model output during its own
 * validation. Carried by {@link AiProviderException} (category {@code INVALID_STRUCTURED_RESPONSE}) so operational
 * and evaluation tooling can tell an unsafe or off-request output from a genuinely malformed one without ever
 * seeing model text. {@link #OUTPUT_TOKEN_LIMIT} is the one reason for an output that is not parseable: the provider
 * stopped it at the configured output-token ceiling, so the response is incomplete rather than wrong.
 */
public enum AiOutputRejection {
    ACTION_NOT_ALLOWED,
    ACTION_MISMATCH,
    INTENT_MISMATCH,
    LOCALE_MISMATCH,
    UNSAFE_CONTENT,
    OUTPUT_TOKEN_LIMIT
}
