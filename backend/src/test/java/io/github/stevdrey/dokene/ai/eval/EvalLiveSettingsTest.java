package io.github.stevdrey.dokene.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EvalLiveSettingsTest {
    @Test
    void defaultsToHalfAndAcceptsValuesInTheOpenClosedUnitInterval() {
        assertThat(EvalLiveSettings.minSuccessRatio(null)).isEqualTo(0.5);
        assertThat(EvalLiveSettings.minSuccessRatio(" ")).isEqualTo(0.5);
        assertThat(EvalLiveSettings.minSuccessRatio("0.8")).isEqualTo(0.8);
        assertThat(EvalLiveSettings.minSuccessRatio("1")).isEqualTo(1.0);
    }

    @Test
    void rejectsNonPositiveNonFiniteAboveOneAndMalformedOverrides() {
        for (String bad : new String[] {"0", "0.0", "-0.5", "1.01", "NaN", "Infinity", "-Infinity", "abc"}) {
            assertThatThrownBy(() -> EvalLiveSettings.minSuccessRatio(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void priceTableIsAbsentOrCompleteFiniteAndNonNegative() {
        assertThat(EvalLiveSettings.pricing(null, null)).isNull();
        assertThat(EvalLiveSettings.pricing(" ", "")).isNull();
        assertThat(EvalLiveSettings.pricing("1.5", "0")).isEqualTo(new EvalReportBuilder.Pricing(1.5, 0.0));
        for (String[] bad : new String[][] {{"1", null}, {null, "2"}, {"-1", "2"}, {"1", "-0.1"}, {"NaN", "2"},
                {"1", "Infinity"}, {"abc", "2"}}) {
            assertThatThrownBy(() -> EvalLiveSettings.pricing(bad[0], bad[1]))
                    .as(String.join("/", String.valueOf(bad[0]), String.valueOf(bad[1])))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
