// ABOUTME: The pure admission rules (ADR 0040, spec 0024 AC-2 to AC-4 and AC-8): the effective tenant minute limit and
// ABOUTME: its reason, when a window is full, the retry delay at the edges and after a lowered limit, and the pause.
package io.github.stevdrey.dokene.admission;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AdmissionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");

    @ParameterizedTest
    @CsvSource({
            // tenantPerMinute, globalPerMinute, sharePercent, share, limit, reason
            "30, 120, 25, 30, 30, TENANT_MINUTE",
            "30, 100, 25, 25, 25, TENANT_SHARE",
            "10, 120, 25, 30, 10, TENANT_MINUTE",
            "30, 3, 25, 1, 1, TENANT_SHARE",
            "30, 7, 50, 3, 3, TENANT_SHARE",
            "30, 120, 100, 120, 30, TENANT_MINUTE",
            "1, 1, 1, 1, 1, TENANT_MINUTE"})
    void theTenantMinuteLimitIsTheSmallerOfItsOwnLimitAndItsShare(int tenantPerMinute, int globalPerMinute,
            int sharePercent, int share, int limit, AdmissionDenialReason reason) {
        AdmissionPolicy policy = policy(tenantPerMinute, globalPerMinute, sharePercent);

        assertThat(policy.tenantShare()).isEqualTo(share);
        assertThat(policy.tenantMinuteLimit()).isEqualTo(limit);
        assertThat(policy.tenantMinuteReason()).isEqualTo(reason);
    }

    @Test
    void aWindowUnderItsLimitHasRoom() {
        assertThat(policy().full(new WindowUsage(2, null), 3, AdmissionPolicy.MINUTE, NOW,
                AdmissionDenialReason.TENANT_MINUTE)).isEmpty();
    }

    @Test
    void aFullWindowIsDeniedUntilTheRowThatFreesAUnitLeavesIt() {
        Optional<Denied> denied = policy().full(new WindowUsage(3, NOW.minusSeconds(40)), 3,
                AdmissionPolicy.MINUTE, NOW, AdmissionDenialReason.TENANT_MINUTE);

        assertThat(denied).contains(new Denied(AdmissionDenialReason.TENANT_MINUTE, Duration.ofSeconds(20)));
    }

    @Test
    void aDayWindowCanAskForManyHours() {
        Optional<Denied> denied = policy().full(new WindowUsage(500, NOW.minus(Duration.ofHours(2))), 500,
                AdmissionPolicy.DAY, NOW, AdmissionDenialReason.TENANT_DAY);

        assertThat(denied).contains(new Denied(AdmissionDenialReason.TENANT_DAY, Duration.ofHours(22)));
    }

    @Test
    void theRetryDelayIsNeverBelowOneSecond() {
        Optional<Denied> denied = policy().full(new WindowUsage(3, NOW.minusSeconds(60).plusMillis(200)), 3,
                AdmissionPolicy.MINUTE, NOW, AdmissionDenialReason.GLOBAL_MINUTE);

        assertThat(denied).contains(new Denied(AdmissionDenialReason.GLOBAL_MINUTE, Duration.ofSeconds(1)));
    }

    @Test
    void aWindowOverItsLimitAfterTheLimitWasLoweredIsStillDenied() {
        Optional<Denied> denied = policy().full(new WindowUsage(5, NOW.minusSeconds(10)), 2,
                AdmissionPolicy.MINUTE, NOW, AdmissionDenialReason.TENANT_MINUTE);

        assertThat(denied).contains(new Denied(AdmissionDenialReason.TENANT_MINUTE, Duration.ofSeconds(50)));
    }

    @Test
    void aRowCountsOnlyStrictlyAfterTheWindowStart() {
        assertThat(policy().windowStart(NOW, AdmissionPolicy.MINUTE)).isEqualTo(NOW.minusSeconds(60));
        assertThat(policy().windowStart(NOW, AdmissionPolicy.DAY)).isEqualTo(NOW.minus(Duration.ofHours(24)));
    }

    @Test
    void aPauseInTheFutureDeniesWithTheTimeLeft() {
        assertThat(policy().paused(Optional.of(NOW.plusSeconds(42)), NOW))
                .contains(new Denied(AdmissionDenialReason.TENANT_PAUSED, Duration.ofSeconds(42)));
        assertThat(policy().paused(Optional.of(NOW.plusMillis(10)), NOW))
                .contains(new Denied(AdmissionDenialReason.TENANT_PAUSED, Duration.ofSeconds(1)));
    }

    @Test
    void aPauseThatEndedOrNeverExistedDeniesNothing() {
        assertThat(policy().paused(Optional.of(NOW), NOW)).isEmpty();
        assertThat(policy().paused(Optional.of(NOW.minusSeconds(1)), NOW)).isEmpty();
        assertThat(policy().paused(Optional.empty(), NOW)).isEmpty();
    }

    @Test
    void aSwitchDenialPromisesNoTimeSoItAsksForOneSecond() {
        assertThat(policy().switchOff())
                .isEqualTo(new Denied(AdmissionDenialReason.SWITCH_OFF, Duration.ofSeconds(1)));
    }

    private static AdmissionPolicy policy() {
        return policy(30, 120, 25);
    }

    private static AdmissionPolicy policy(int tenantPerMinute, int globalPerMinute, int sharePercent) {
        return new AdmissionPolicy(new AdmissionProperties(
                new AdmissionProperties.Send(tenantPerMinute, 500, globalPerMinute, sharePercent, null), null, null,
                null));
    }
}
