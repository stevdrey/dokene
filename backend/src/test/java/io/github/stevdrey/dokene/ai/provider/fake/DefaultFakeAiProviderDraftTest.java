package io.github.stevdrey.dokene.ai.provider.fake;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.DraftContext;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.application.TrustedBusinessFacts;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultFakeAiProviderDraftTest {

    @Test
    void truncatesLongProductDescriptionsOnCodePointBoundaries() {
        String product = "a".repeat(179) + "😀" + "tail";
        var trusted = new RecommendationContext.TrustedFacts(
                LocalDate.of(2026, 9, 28), "DUE", List.of(TrustedFollowUpReason.DUE_TODAY),
                30, LocalDate.of(2026, 9, 28), true, List.of(Instant.parse("2026-08-01T12:00:00Z")),
                Arrays.asList(SemanticAction.values()));
        var untrusted = new RecommendationContext.UntrustedText("Ana", null, List.of(product));
        var context = new DraftContext(new RecommendationContext(trusted, untrusted),
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP, SemanticTemplateIntent.REPEAT_PURCHASE,
                new TrustedBusinessFacts("Tienda", "es-419"));

        var response = new DefaultFakeAiProvider().draft(new AiDraftRequest(context, Duration.ofSeconds(3)));

        MessageDraft draft = (MessageDraft) response.outcome();
        assertThat(draft.evidence()).allSatisfy(item -> assertThat(isWellFormed(item)).isTrue());
        assertThat(isWellFormed(draft.body())).isTrue();
    }

    @Test
    void returnsExplicitRefusalForUnsupportedLocale() {
        var trusted = new RecommendationContext.TrustedFacts(
                LocalDate.of(2026, 9, 28), "DUE", List.of(TrustedFollowUpReason.DUE_TODAY),
                30, LocalDate.of(2026, 9, 28), true, List.of(Instant.parse("2026-08-01T12:00:00Z")),
                Arrays.asList(SemanticAction.values()));
        var untrusted = new RecommendationContext.UntrustedText("Ana", null, List.of("Café"));
        var context = new DraftContext(new RecommendationContext(trusted, untrusted),
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP, SemanticTemplateIntent.REPEAT_PURCHASE,
                new TrustedBusinessFacts("Tienda", "pt-BR"));

        var response = new DefaultFakeAiProvider().draft(new AiDraftRequest(context, Duration.ofSeconds(3)));

        assertThat(response.outcome()).isInstanceOf(io.github.stevdrey.dokene.ai.domain.NoDraft.class);
    }

    private static boolean isWellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
