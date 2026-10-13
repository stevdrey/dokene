package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import java.util.Optional;

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
                                    io.github.stevdrey.dokene.ai.domain.DraftOutcome outcome,
                                    io.github.stevdrey.dokene.ai.domain.SemanticAction expectedAction,
                                    io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent expectedIntent,
                                    String expectedLocale);

    default DraftGateDecision evaluateDraft(CustomerId customerId,
                                            RecommendationContextAssembler.Assembly assembly,
                                            io.github.stevdrey.dokene.ai.domain.DraftOutcome outcome,
                                            io.github.stevdrey.dokene.ai.domain.SemanticAction expectedAction,
                                            io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent expectedIntent) {
        return evaluateDraft(customerId, assembly, outcome, expectedAction, expectedIntent, null);
    }

    default DraftGateDecision evaluateDraft(CustomerId customerId,
                                            RecommendationContextAssembler.Assembly assembly,
                                            io.github.stevdrey.dokene.ai.domain.DraftOutcome outcome) {
        return evaluateDraft(customerId, assembly, outcome, null, null, null);
    }

    default DraftGateDecision evaluateDraft(CustomerId customerId, io.github.stevdrey.dokene.ai.domain.DraftOutcome outcome) {
        return evaluateDraft(customerId, null, outcome, null, null);
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

    /**
     * Deterministic customer-level re-check run before every provider retry, after the authorization
     * revalidation. It applies the same hard constraints the gate enforces on the result (archive,
     * do-not-contact, WhatsApp consent, follow-up eligibility and state drift against the baseline) so a
     * retry never transmits customer context after the customer stopped being eligible.
     *
     * @param customerId target customer identifier
     * @param assembly baseline assembly used when the first attempt was sent, if available
     * @return the closed reason that blocks the retry, or empty when the retry may proceed
     */
    default Optional<ActionGateRejectionReason> retryBlockingReason(CustomerId customerId,
                                                                    RecommendationContextAssembler.Assembly assembly) {
        return Optional.empty();
    }
}
