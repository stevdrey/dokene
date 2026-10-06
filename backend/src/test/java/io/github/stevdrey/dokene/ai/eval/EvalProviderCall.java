package io.github.stevdrey.dokene.ai.eval;

import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOutputRejection;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;

/**
 * One provider invocation as seen at the {@code AiProvider} port: the raw (pre-gate, untrusted) outcome or the
 * failure category, plus the provider-reported metadata. {@code outcome} is a {@code RecommendationOutcome} or a
 * {@code DraftOutcome}, null on failure. Never persisted verbatim in reports (customer-like text stays out).
 */
public record EvalProviderCall(
        String displayName,
        AiOperation operation,
        RecommendationContext context,
        Object outcome,
        AiFailureCategory failure,
        AiInvocationMetadata metadata,
        long wallLatencyNanos,
        SemanticAction requestedAction,
        SemanticTemplateIntent requestedIntent,
        AiOutputRejection rejection) {

    /** Call without an adapter rejection detail. */
    public EvalProviderCall(String displayName, AiOperation operation, RecommendationContext context, Object outcome,
            AiFailureCategory failure, AiInvocationMetadata metadata, long wallLatencyNanos,
            SemanticAction requestedAction, SemanticTemplateIntent requestedIntent) {
        this(displayName, operation, context, outcome, failure, metadata, wallLatencyNanos, requestedAction,
                requestedIntent, null);
    }

    /** Call without a requested draft action/intent (recommendations, failures before a request existed). */
    public EvalProviderCall(String displayName, AiOperation operation, RecommendationContext context, Object outcome,
            AiFailureCategory failure, AiInvocationMetadata metadata, long wallLatencyNanos) {
        this(displayName, operation, context, outcome, failure, metadata, wallLatencyNanos, null, null, null);
    }
}
