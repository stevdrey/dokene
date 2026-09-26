package io.github.stevdrey.dokene.ai.application;

import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
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
            new RecommendationContext.TrustedFacts(LocalDate.of(2026, 9, 25), "DUE",
                    List.of(TrustedFollowUpReason.DUE_TODAY), 30, LocalDate.of(2026, 9, 25), true,
                    List.of(Instant.parse("2026-09-01T12:00:00Z")),
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
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, LocalDate.of(2026, 9, 25), true,
                Collections.nCopies(5, Instant.parse("2026-09-01T12:00:00Z")),
                List.of(SemanticAction.GENERAL_CHECK_IN));
        assertThatThrownBy(() -> new RecommendationContext(facts,
                new RecommendationContext.UntrustedText("Customer", null, descriptions)))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.TOO_LARGE));
    }

    @Test
    void rejectsNonPositiveOrExcessiveCadenceInTrustedFacts() {
        for (int invalidCadence : List.of(0, -1, -30, 3_651, Integer.MAX_VALUE)) {
            assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(LocalDate.of(2026, 9, 25), "DUE",
                    List.of(TrustedFollowUpReason.DUE_TODAY), invalidCadence, LocalDate.of(2026, 9, 25), true,
                    List.of(Instant.parse("2026-09-01T12:00:00Z")),
                    List.of(SemanticAction.GENERAL_CHECK_IN)))
                    .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));
        }

        var maxCadenceFacts = new RecommendationContext.TrustedFacts(LocalDate.of(2026, 9, 25), "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 3_650, LocalDate.of(2026, 9, 25), true,
                List.of(Instant.parse("2026-09-01T12:00:00Z")),
                List.of(SemanticAction.GENERAL_CHECK_IN));
        assertThat(maxCadenceFacts.effectiveCadenceDays()).isEqualTo(3_650);
    }

    @Test
    void rejectsIneligibleValuesInTrustedFacts() {
        LocalDate date = LocalDate.of(2026, 9, 25);
        List<Instant> purchases = List.of(Instant.parse("2026-09-01T12:00:00Z"));
        List<SemanticAction> actions = List.of(SemanticAction.GENERAL_CHECK_IN);

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, date, false, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        for (String invalidStatus : List.of("INELIGIBLE", "NOT_YET_DUE", "OTHER")) {
            assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, invalidStatus,
                    List.of(TrustedFollowUpReason.DUE_TODAY), 30, date, true, purchases, actions))
                    .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                            assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));
        }

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, null, true, purchases, actions))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, date, true, purchases, List.of()))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        // Empty reasons or excessive reasons (> 5)
        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(), 30, date, true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                Collections.nCopies(6, TrustedFollowUpReason.DUE_TODAY), 30, date, true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        // DUE requires dueDate == tenantDate and reason DUE_TODAY
        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, date.minusDays(1), true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, date.plusDays(1), true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.OVERDUE), 30, date, true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        // OVERDUE requires dueDate < tenantDate and reason OVERDUE
        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "OVERDUE",
                List.of(TrustedFollowUpReason.OVERDUE), 30, date, true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "OVERDUE",
                List.of(TrustedFollowUpReason.OVERDUE), 30, date.plusDays(1), true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "OVERDUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, date.minusDays(1), true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        // Contradictory reasons or duplicate reasons
        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY, TrustedFollowUpReason.OVERDUE), 30, date, true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY, TrustedFollowUpReason.DUE_TODAY), 30, date, true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "OVERDUE",
                List.of(TrustedFollowUpReason.OVERDUE, TrustedFollowUpReason.DUE_TODAY), 30, date.minusDays(1), true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "OVERDUE",
                List.of(TrustedFollowUpReason.OVERDUE, TrustedFollowUpReason.OVERDUE), 30, date.minusDays(1), true, purchases, actions))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        // Duplicate or excessive allowed actions
        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, date, true, purchases,
                List.of(SemanticAction.GENERAL_CHECK_IN, SemanticAction.GENERAL_CHECK_IN)))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        assertThatThrownBy(() -> new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, date, true, purchases,
                Collections.nCopies(6, SemanticAction.GENERAL_CHECK_IN)))
                .isInstanceOfSatisfying(RecommendationContextException.class, failure ->
                        assertThat(failure.reason()).isEqualTo(RecommendationContextException.Reason.UNSUPPORTED));

        // Valid DUE and OVERDUE cases
        var validDue = new RecommendationContext.TrustedFacts(date, "DUE",
                List.of(TrustedFollowUpReason.DUE_TODAY), 30, date, true, purchases, actions);
        assertThat(validDue.followUpStatus()).isEqualTo("DUE");

        var validOverdue = new RecommendationContext.TrustedFacts(date, "OVERDUE",
                List.of(TrustedFollowUpReason.OVERDUE), 30, date.minusDays(1), true, purchases, actions);
        assertThat(validOverdue.followUpStatus()).isEqualTo("OVERDUE");
    }

    @Test
    void untrustedTextCalculatesTotalLengthAndValidates() {
        var text = new RecommendationContext.UntrustedText("Customer", "Notes", List.of("P1", "P2"));
        assertThat(text.totalTextLength()).isEqualTo("Customer".length() + "Notes".length() + 2 + 2);

        var withoutNotes = new RecommendationContext.UntrustedText("Customer", null, List.of("P1"));
        assertThat(withoutNotes.totalTextLength()).isEqualTo("Customer".length() + 2);
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
