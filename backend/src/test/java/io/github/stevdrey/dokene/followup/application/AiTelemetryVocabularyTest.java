package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.ai.application.AiTelemetry;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** The AI module cannot import {@link ActionGateRejectionReason}, so its metric allow-list is guarded here. */
class AiTelemetryVocabularyTest {
    @Test
    void metricGateReasonAllowListMatchesTheActionGateRejectionReasons() {
        assertThat(AiTelemetry.GATE_REJECTION_REASONS).isEqualTo(
                Arrays.stream(ActionGateRejectionReason.values()).map(Enum::name).collect(Collectors.toSet()));
    }
}
