package io.github.stevdrey.dokene.ai.application;

/**
 * Closed, content-free reason why an adapter rejected a structurally parseable model output during its own
 * validation. Carried by {@link AiProviderException} (category {@code INVALID_STRUCTURED_RESPONSE}) so operational
 * and evaluation tooling can tell an unsafe or off-request output from a genuinely malformed one without ever
 * seeing model text.
 */
public enum AiOutputRejection {
    ACTION_NOT_ALLOWED,
    ACTION_MISMATCH,
    INTENT_MISMATCH,
    LOCALE_MISMATCH,
    UNSAFE_CONTENT
}
