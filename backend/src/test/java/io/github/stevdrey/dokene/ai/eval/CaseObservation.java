package io.github.stevdrey.dokene.ai.eval;

import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
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
        List<EvalProviderCall> calls) {
    public CaseObservation {
        calls = calls == null ? List.of() : List.copyOf(calls);
    }

    /** Whether authoritative setup forbids contact; derived from the setup, never from the expectation block. */
    public boolean contactForbidden() {
        EvalCase.Setup setup = evalCase.setup();
        return setup.archived() || setup.doNotContact() || !"GRANTED".equals(setup.consent());
    }
}
