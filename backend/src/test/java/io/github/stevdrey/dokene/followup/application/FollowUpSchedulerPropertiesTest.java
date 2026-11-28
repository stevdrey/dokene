// ABOUTME: Pins the follow-up scheduler settings (issue 463, ADR 0040): off by default, kill switches beneath the master
// ABOUTME: switch, and startup validation of every bound and of the worker label.
package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FollowUpSchedulerPropertiesTest {

    @Test
    void defaultsToOffWithBoundedWork() {
        FollowUpSchedulerProperties defaults = new FollowUpSchedulerProperties(null, null, null, null, null, null, null,
                null, null);

        assertThat(defaults.enabled()).isFalse();
        assertThat(defaults.generationActive()).isFalse();
        assertThat(defaults.processingActive()).isFalse();
        assertThat(defaults.interval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.tenantsPerTick()).isEqualTo(50);
        assertThat(defaults.workBatchSize()).isEqualTo(10);
        assertThat(defaults.maxTickDuration()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.shutdownTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(defaults.workerId()).matches("dokene-[0-9a-f]{8}");
    }

    @Test
    void theStageSwitchesOnlyApplyBeneathTheMasterSwitch() {
        assertThat(properties(false, true, true).generationActive()).isFalse();
        assertThat(properties(false, true, true).processingActive()).isFalse();
        assertThat(properties(true, null, null).generationActive()).isTrue();
        assertThat(properties(true, null, null).processingActive()).isTrue();
        assertThat(properties(true, false, true).generationActive()).isFalse();
        assertThat(properties(true, true, false).processingActive()).isFalse();
    }

    @Test
    void keepsAnOperatorChosenWorkerLabelAndRefusesAnUnsafeOne() {
        assertThat(new FollowUpSchedulerProperties(null, null, null, null, null, null, null, null, " pod-a ")
                .worker().value()).isEqualTo("pod-a");
        assertThatThrownBy(() -> new FollowUpSchedulerProperties(null, null, null, null, null, null, null, null,
                "owner@example.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpSchedulerProperties(null, null, null, null, null, null, null, null,
                "x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesOutOfRangeBounds() {
        assertInvalid(Duration.ofSeconds(9), 50, 10, null, null);
        assertInvalid(Duration.ofHours(2), 50, 10, null, null);
        assertInvalid(null, 0, 10, null, null);
        assertInvalid(null, 501, 10, null, null);
        assertInvalid(null, 50, 0, null, null);
        assertInvalid(null, 50, 201, null, null);
        assertInvalid(null, 50, 10, Duration.ofMillis(999), null);
        assertInvalid(null, 50, 10, Duration.ofMinutes(31), null);
        assertInvalid(null, 50, 10, null, Duration.ofSeconds(-1));
        assertInvalid(null, 50, 10, null, Duration.ofMinutes(6));
    }

    private static FollowUpSchedulerProperties properties(Boolean enabled, Boolean generation, Boolean processing) {
        return new FollowUpSchedulerProperties(enabled, generation, processing, null, null, null, null, null, null);
    }

    private static void assertInvalid(Duration interval, int tenantsPerTick, int workBatchSize, Duration maxTick,
            Duration shutdown) {
        assertThatThrownBy(() -> new FollowUpSchedulerProperties(true, null, null, interval, tenantsPerTick,
                workBatchSize, maxTick, shutdown, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
