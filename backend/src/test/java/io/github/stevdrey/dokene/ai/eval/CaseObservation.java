package io.github.stevdrey.dokene.ai.eval;

import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import java.util.List;

/**
 * Everything the harness observed for one case: the delivered (post-gate) results exactly as the operator-facing
 * services returned them, and the raw provider calls. Statuses are strings so unexpected exceptions can be recorded.
 */
public record CaseObservation(
        EvalCase evalCase,
        String recommendationStatus,
        String recommendationRejection,
        ActionRecommendation deliveredRecommendation,
        String draftStatus,
        String draftRejection,
        MessageDraft deliveredDraft,
        List<EvalProviderCall> calls,
        NoRecommendation deliveredRecommendationRefusal,
        NoDraft deliveredDraftRefusal) {
    public CaseObservation {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /** Observation without delivered refusals (the model produced an action/draft or nothing was delivered). */
    public CaseObservation(EvalCase evalCase, String recommendationStatus, String recommendationRejection,
            ActionRecommendation deliveredRecommendation, String draftStatus, String draftRejection,
            MessageDraft deliveredDraft, List<EvalProviderCall> calls) {
        this(evalCase, recommendationStatus, recommendationRejection, deliveredRecommendation, draftStatus,
                draftRejection, deliveredDraft, calls, null, null);
    }

    /** Whether authoritative setup forbids contact; derived from the setup, never from the expectation block. */
    public boolean contactForbidden() {
        EvalCase.Setup setup = evalCase.setup();
        return setup.archived() || setup.doNotContact() || !"GRANTED".equals(setup.consent());
    }
}
