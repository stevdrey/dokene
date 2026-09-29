package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.customer.domain.CustomerId;

/**
 * Deterministic application-owned policy gate enforcing ADR 0004 for recommendations and drafts.
 * AI output is strictly advisory and untrusted until validated against current authoritative state.
 */
public interface AiActionGate {

    /**
     * Evaluates a recommendation outcome against current authoritative application state.
     *
     * @param customerId target customer identifier
     * @param assembly baseline assembly used when the AI provider was invoked, if available
     * @param outcome advisory recommendation outcome returned by the AI provider
     * @return deterministic gate decision (Accepted or Rejected with typed reason)
     */
    ActionGateDecision evaluate(CustomerId customerId,
                                RecommendationContextAssembler.Assembly assembly,
                                RecommendationOutcome outcome);

    default ActionGateDecision evaluate(CustomerId customerId, RecommendationOutcome outcome) {
        return evaluate(customerId, null, outcome);
    }

    /**
     * Evaluates a draft outcome against current authoritative application state.
     *
     * @param customerId target customer identifier
     * @param assembly baseline assembly used when the AI provider was invoked, if available
     * @param outcome advisory draft outcome returned by the AI provider
     * @return deterministic draft gate decision (Accepted or Rejected with typed reason)
     */
    DraftGateDecision evaluateDraft(CustomerId customerId,
                                    RecommendationContextAssembler.Assembly assembly,
                                    io.github.stevdrey.dokene.ai.domain.DraftOutcome outcome);

    default DraftGateDecision evaluateDraft(CustomerId customerId, io.github.stevdrey.dokene.ai.domain.DraftOutcome outcome) {
        return evaluateDraft(customerId, null, outcome);
    }

    /**
     * Revalidates caller authorization and tenant active status for customer operations.
     *
     * @param customerId target customer identifier
     */
    default void revalidateAuthorization(CustomerId customerId) {
    }

    /**
     * Revalidates caller draft authorization and tenant active status for customer operations.
     *
     * @param customerId target customer identifier
     */
    default void revalidateDraftAuthorization(CustomerId customerId) {
    }
}
