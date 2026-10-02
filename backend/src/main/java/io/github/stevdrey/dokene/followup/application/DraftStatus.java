package io.github.stevdrey.dokene.followup.application;

/**
 * High-level status of a follow-up message draft request.
 */
public enum DraftStatus {
    /**
     * An actionable message draft was returned by the AI provider and accepted by the AI Action Gate.
     */
    AVAILABLE,

    /**
     * The customer is deterministically eligible, but the AI model returned an explicit refusal or no-draft outcome.
     */
    NO_DRAFT,

    /**
     * The customer is deterministically ineligible for follow-up (e.g. no contact consent, DNC, not yet due, archived).
     */
    INELIGIBLE,

    /**
     * The draft was rejected because underlying customer state, purchase history, or policy changed.
     */
    STALE_STATE,

    /**
     * The AI provider is temporarily unavailable, timed out, or encountered an unrecoverable failure.
     */
    AI_UNAVAILABLE
}
