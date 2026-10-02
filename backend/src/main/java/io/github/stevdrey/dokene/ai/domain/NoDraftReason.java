package io.github.stevdrey.dokene.ai.domain;

/**
 * Closed set of reasons explaining why no follow-up draft was generated.
 */
public enum NoDraftReason {
    INSUFFICIENT_HISTORY,
    UNSUPPORTED_ACTION,
    MISSING_TRUSTED_FACTS,
    SAFETY_VIOLATION,
    MANUAL_REVIEW_REQUIRED
}
