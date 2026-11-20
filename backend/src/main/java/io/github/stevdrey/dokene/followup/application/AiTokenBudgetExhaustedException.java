package io.github.stevdrey.dokene.followup.application;

/**
 * The active tenant has used its monthly AI token budget (ADR 0035). Raised before any provider call, so no customer
 * context leaves the system; the caller degrades to {@code AI_UNAVAILABLE} with
 * {@link AiUnavailableReason#TOKEN_BUDGET_EXHAUSTED} and the deterministic workflow stays available.
 */
public final class AiTokenBudgetExhaustedException extends RuntimeException {
    public AiTokenBudgetExhaustedException() {
        super("Tenant AI token budget exhausted", null, false, false);
    }
}
