package io.github.stevdrey.dokene.followup.application;

/**
 * High-level status of a Next Best Action recommendation request,
 * enabling client UIs to reliably distinguish actionable advice from refusals,
 * staleness, ineligibility, or temporary provider downtime.
 */
public enum RecommendationStatus {
    /**
     * An actionable recommendation was returned by the AI provider and accepted by the AI Action Gate.
     */
    AVAILABLE,

    /**
     * The customer is deterministically eligible, but the AI model returned an explicit refusal or no-recommendation.
     */
    NO_RECOMMENDATION,

    /**
     * The customer is deterministically ineligible for follow-up (e.g. no contact consent, DNC, not yet due, archived).
     */
    INELIGIBLE,

    /**
     * The recommendation was rejected because the underlying customer state, purchase history, or policy changed.
     */
    STALE_STATE,

    /**
     * The AI provider is temporarily unavailable, timed out, or encountered an unrecoverable failure.
     * The deterministic follow-up queue and manual workflow remain functional.
     */
    AI_UNAVAILABLE
}
