package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.DraftContext;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.application.TrustedBusinessFacts;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingAiProviderTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

    private static RecommendationContext context() {
        var trusted = new RecommendationContext.TrustedFacts(TODAY, "OVERDUE", List.of(TrustedFollowUpReason.OVERDUE), 30,
                TODAY.minusDays(1), true, List.of(Instant.parse("2026-08-01T12:00:00Z")),
                Arrays.asList(SemanticAction.values()));
        return new RecommendationContext(trusted,
                new RecommendationContext.UntrustedText("Lucía Demo-01", null, List.of("Café")));
    }

    private final AiProvider crashing = new AiProvider() {
        @Override
        public AiRecommendationResponse recommend(AiRecommendationRequest request) {
            throw new IllegalStateException("driver exploded");
        }

        @Override
        public io.github.stevdrey.dokene.ai.application.AiDraftResponse draft(AiDraftRequest request) {
            throw new IllegalStateException("driver exploded");
        }
    };

    @Test
    void recordsUntypedRecommendationFailuresAsUnavailableAttemptsAndRethrows() {
        RecordingAiProvider recorder = new RecordingAiProvider(crashing);
        var request = new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION, context(), Duration.ofSeconds(1));

        assertThatThrownBy(() -> recorder.recommend(request)).isInstanceOf(IllegalStateException.class);

        assertThat(recorder.callsFor("Lucía Demo-01")).singleElement().satisfies(call -> {
            assertThat(call.failure()).isEqualTo(AiFailureCategory.UNAVAILABLE);
            assertThat(call.metadata()).isNull();
            assertThat(call.outcome()).isNull();
            assertThat(call.wallLatencyNanos()).isNotNegative();
        });
    }

    @Test
    void recordsUntypedDraftFailuresWithTheRequestedActionAndRethrows() {
        RecordingAiProvider recorder = new RecordingAiProvider(crashing);
        var request = new AiDraftRequest(new DraftContext(context(), SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.REPEAT_PURCHASE, new TrustedBusinessFacts("Tienda Demo")), Duration.ofSeconds(1));

        assertThatThrownBy(() -> recorder.draft(request)).isInstanceOf(IllegalStateException.class);

        assertThat(recorder.callsFor("Lucía Demo-01")).singleElement().satisfies(call -> {
            assertThat(call.operation()).isEqualTo(AiOperation.MESSAGE_DRAFT);
            assertThat(call.failure()).isEqualTo(AiFailureCategory.UNAVAILABLE);
            assertThat(call.requestedAction()).isEqualTo(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP);
            assertThat(call.requestedIntent()).isEqualTo(SemanticTemplateIntent.REPEAT_PURCHASE);
        });
        assertThat(InvariantChecker.rawFindings(new CaseObservation(
                EvalDatasetLoader.loadDefault().cases().getFirst(), "AI_UNAVAILABLE", null, null, "AI_UNAVAILABLE", null,
                null, recorder.callsFor("Lucía Demo-01"))).providerFailure()).isTrue();
    }
}
