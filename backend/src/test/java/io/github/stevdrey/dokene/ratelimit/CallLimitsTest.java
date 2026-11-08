package io.github.stevdrey.dokene.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CallLimitsTest {
    private static final CallLimits DEFAULTS = new CallLimits(30, 20, Duration.ofMinutes(15));

    @Test
    void missingValuesTakeTheDefaultsAndGivenOnesWin() {
        assertThat(CallLimits.resolve(null, null, null, DEFAULTS)).isEqualTo(DEFAULTS);
        assertThat(CallLimits.resolve(5, null, Duration.ofMinutes(1), DEFAULTS))
                .isEqualTo(new CallLimits(5, 20, Duration.ofMinutes(1)));
    }

    @Test
    void nonPositiveLimitsOrWindowsAreRefused() {
        assertThatThrownBy(() -> CallLimits.resolve(0, null, null, DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallLimits.resolve(null, -1, null, DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallLimits.resolve(null, null, Duration.ZERO, DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CallLimits.resolve(null, null, Duration.ofSeconds(-1), DEFAULTS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallLimits(1, 1, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CallLimits.resolve(1, 1, Duration.ofMinutes(1), null))
                .isInstanceOf(NullPointerException.class);
    }
}
