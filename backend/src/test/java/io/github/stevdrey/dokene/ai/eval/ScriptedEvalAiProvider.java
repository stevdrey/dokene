package io.github.stevdrey.dokene.ai.eval;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraftReason;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Network-free provider replaying the per-case {@code script} of the dataset, including deliberately unsafe output.
 * Cases are matched by the synthetic customer's unique display name carried in the provider-bound context.
 * Latency and token usage are fixed constants so deterministic reports are reproducible.
 */
public final class ScriptedEvalAiProvider implements AiProvider {
    public static final String PROVIDER_ID = "scripted-eval";
    public static final String MODEL_ID = "scripted-v1";

    private final Map<String, EvalCase> byName = new HashMap<>();

    public ScriptedEvalAiProvider(EvalDataset dataset) {
        dataset.cases().forEach(c -> byName.put(c.displayName(), c));
    }

    @Override
    public AiRecommendationResponse recommend(AiRecommendationRequest request) {
        EvalCase evalCase = byName.get(request.context().untrusted().displayName());
        if (evalCase == null || evalCase.script() == null || evalCase.script().recommendation() == null) {
            throw failure(AiFailureCategory.NOT_AVAILABLE);
        }
        EvalCase.RecommendationScript script = evalCase.script().recommendation();
        return switch (script.kind()) {
            case "ACTION" -> new AiRecommendationResponse(new ActionRecommendation(script.action(),
                    script.templateIntent(), script.rationale(), RecommendationConfidence.of(script.confidence()),
                    DraftVariables.empty()), metadata());
            case "NO_RECOMMENDATION" -> new AiRecommendationResponse(new NoRecommendation(
                    NoRecommendationReason.valueOf(script.reason()), script.rationale(),
                    RecommendationConfidence.of(script.confidence())), metadata());
            case "FAILURE" -> throw failure(AiFailureCategory.valueOf(script.failure()));
            default -> throw new IllegalStateException("Unknown recommendation script kind: " + script.kind());
        };
    }

    @Override
    public AiDraftResponse draft(AiDraftRequest request) {
        EvalCase evalCase = byName.get(request.context().customerContext().untrusted().displayName());
        if (evalCase == null || evalCase.script() == null || evalCase.script().draft() == null) {
            throw failure(AiFailureCategory.NOT_AVAILABLE);
        }
        EvalCase.DraftScript script = evalCase.script().draft();
        return switch (script.kind()) {
            case "DRAFT" -> new AiDraftResponse(new MessageDraft(script.action(), script.templateIntent(),
                    script.body(), DraftVariables.empty(), script.locale(), orEmpty(script.evidence()),
                    orEmpty(script.warnings()), script.rationale(), RecommendationConfidence.of(script.confidence())),
                    metadata());
            case "NO_DRAFT" -> new AiDraftResponse(new NoDraft(NoDraftReason.valueOf(script.reason()),
                    script.rationale(), RecommendationConfidence.of(script.confidence())), metadata());
            case "FAILURE" -> throw failure(AiFailureCategory.valueOf(script.failure()));
            default -> throw new IllegalStateException("Unknown draft script kind: " + script.kind());
        };
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static AiProviderException failure(AiFailureCategory category) {
        return new AiProviderException(category, new AiInvocationMetadata(PROVIDER_ID, MODEL_ID, "scripted-request",
                Duration.ofMillis(10), new AiTokenUsage(100, 40), AiCompletionStatus.FAILED));
    }

    private static AiInvocationMetadata metadata() {
        return new AiInvocationMetadata(PROVIDER_ID, MODEL_ID, "scripted-request", Duration.ofMillis(10),
                new AiTokenUsage(100, 40), AiCompletionStatus.SUCCEEDED);
    }
}
