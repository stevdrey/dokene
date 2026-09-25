package io.github.stevdrey.dokene.ai.application;

import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiProviderContractTest {
    private final RecommendationContext context = new RecommendationContext(
            new RecommendationContext.TrustedFacts(LocalDate.of(2026, 9, 25), "DUE", List.of("DUE_TODAY"),
                    30, LocalDate.of(2026, 9, 25), true, List.of(Instant.parse("2026-09-01T12:00:00Z")),
                    List.of(SemanticAction.GENERAL_CHECK_IN)),
            new RecommendationContext.UntrustedText("Customer", null, List.of("Purchase")));
    private final AiRecommendationRequest request = new AiRecommendationRequest(
            AiOperation.NEXT_BEST_ACTION, context, Duration.ofSeconds(2));
    private final NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.INSUFFICIENT_HISTORY,
            "Not enough history", RecommendationConfidence.of(0.8));

    @Test
    void fakeReturnsTypedOutcomeAndSafeDiagnostics() {
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(refusal);

        AiRecommendationResponse response = fake.recommend(request);

        assertThat(response.outcome()).isSameAs(refusal);
        assertThat(response.metadata()).isEqualTo(new AiInvocationMetadata(
                "fake", "test-model", "test-request-1", Duration.ofMillis(12),
                new AiTokenUsage(11, 7), AiCompletionStatus.SUCCEEDED));
        assertThat(fake.lastRequest()).isSameAs(request);
        assertThat(fake.invocationCount()).isEqualTo(1);
        assertThat(AiRecommendationRequest.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("operation", "context", "timeout");
        assertThat(AiInvocationMetadata.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("providerId", "modelId", "providerRequestId", "latency", "usage", "status");
    }

    @Test
    void validatesEnvelopeAndDiagnosticBounds() {
        assertThatThrownBy(() -> new RecommendationContext(null, context.untrusted()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RecommendationContext(context.trusted(),
                new RecommendationContext.UntrustedText("Customer", null, List.of())))
                .isInstanceOf(RecommendationContextException.class);
        assertThatThrownBy(() -> new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION,
                context, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiTokenUsage(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiInvocationMetadata("fake\nsecret", null, null,
                Duration.ZERO, null, AiCompletionStatus.SUCCEEDED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiInvocationMetadata("fake", null, null,
                Duration.ofMillis(-1), null, AiCompletionStatus.SUCCEEDED)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRecommendationResponse(refusal,
                new AiInvocationMetadata("fake", null, null, Duration.ZERO, null, AiCompletionStatus.FAILED)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExcessivePurchaseCountAndCombinedText() {
        assertThatThrownBy(() -> new RecommendationContext.UntrustedText("Customer", null,
                Collections.nCopies(6, "purchase")))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.TOO_LARGE));
        var descriptions = Collections.nCopies(5, "x".repeat(500));
        var facts = new RecommendationContext.TrustedFacts(LocalDate.of(2026, 9, 25), "DUE",
                List.of("DUE_TODAY"), 30, LocalDate.of(2026, 9, 25), true,
                Collections.nCopies(5, Instant.parse("2026-09-01T12:00:00Z")), List.of());
        assertThatThrownBy(() -> new RecommendationContext(facts,
                new RecommendationContext.UntrustedText("Customer", null, descriptions)))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.TOO_LARGE));
    }

    @Test
    void fakeNormalizesMalformedOutputAndEveryProviderFailure() {
        for (AiFailureCategory category : AiFailureCategory.values()) {
            DeterministicFakeAiProvider fake = category == AiFailureCategory.INVALID_STRUCTURED_RESPONSE
                    ? DeterministicFakeAiProvider.malformedOutput()
                    : DeterministicFakeAiProvider.failure(category);
            assertThatThrownBy(() -> fake.recommend(request))
                    .isInstanceOfSatisfying(AiProviderException.class, failure -> {
                        assertThat(failure.category()).isEqualTo(category);
                        assertThat(failure.metadata().status()).isEqualTo(category == AiFailureCategory.CANCELLED
                                ? AiCompletionStatus.CANCELLED : AiCompletionStatus.FAILED);
                        assertThat(failure.getMessage()).doesNotContain("Not enough history");
                    });
        }
    }

    @Test
    void interruptionIsPreservedAndReportedAsCancellation() {
        DeterministicFakeAiProvider fake = DeterministicFakeAiProvider.success(refusal);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> fake.recommend(request))
                    .isInstanceOfSatisfying(AiProviderException.class, failure ->
                            assertThat(failure.category()).isEqualTo(AiFailureCategory.CANCELLED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
