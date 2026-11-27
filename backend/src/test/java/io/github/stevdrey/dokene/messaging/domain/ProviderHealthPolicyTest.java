// ABOUTME: Walks every row of the provider health transition table (ADR 0038) through the pure policy, plus the
// ABOUTME: outage window, thresholds of one, the cool down sequence, staleness and the send decision.
package io.github.stevdrey.dokene.messaging.domain;

import static io.github.stevdrey.dokene.messaging.domain.ProviderHealthState.DEGRADED;
import static io.github.stevdrey.dokene.messaging.domain.ProviderHealthState.HEALTHY;
import static io.github.stevdrey.dokene.messaging.domain.ProviderHealthState.UNAVAILABLE;
import static io.github.stevdrey.dokene.messaging.domain.ProviderHealthState.UNKNOWN;
import static io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind.OUTAGE;
import static io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind.SUCCESS;
import static io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind.THROTTLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.messaging.domain.ProviderHealthEvent.ProbeFinished;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthEvent.SendSignal;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthEvent.WentStale;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.Decision;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.RefusalReason;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ProviderHealthPolicyTest {
    private static final Instant T0 = Instant.parse("2026-11-25T12:00:00Z");
    private static final Instant NOW = T0.plusSeconds(60);
    private static final Instant LEASE = NOW.plusSeconds(8);

    private final ProviderHealthPolicy enforce = new ProviderHealthPolicy(settings(Mode.ENFORCE, 3, 2));
    private final ProviderHealthPolicy observe = new ProviderHealthPolicy(settings(Mode.OBSERVE, 3, 2));

    // --- send signals while the state is open ------------------------------------------------------------------

    @Test
    void aSuccessWhileHealthyOnlyTouchesTheSignalAndClearsTheBackOffStep() {
        ProviderHealth next = enforce.apply(row(HEALTHY).withCoolDown(null, 2), new SendSignal(SUCCESS), NOW);

        assertThat(next).isEqualTo(row(HEALTHY).withLastSignal(SUCCESS, NOW));
    }

    @Test
    void anOutageWhileHealthyDegradesAndOpensARun() {
        ProviderHealth next = enforce.apply(row(HEALTHY).withSuccesses(4), new SendSignal(OUTAGE), NOW);

        assertThat(next).isEqualTo(row(DEGRADED, NOW).withLastSignal(OUTAGE, NOW).withOutageRun(1, NOW));
    }

    @Test
    void throttlingWhileHealthyDegradesWithoutOpeningAnOutageRun() {
        ProviderHealth next = enforce.apply(row(HEALTHY).withSuccesses(4), new SendSignal(THROTTLED), NOW);

        assertThat(next).isEqualTo(row(DEGRADED, NOW).withLastSignal(THROTTLED, NOW));
    }

    @Test
    void aSuccessWhileDegradedClosesTheOutageRunAndCountsTowardRecovery() {
        ProviderHealth next = enforce.apply(row(DEGRADED).withOutageRun(2, T0), new SendSignal(SUCCESS), NOW);

        assertThat(next).isEqualTo(row(DEGRADED).withLastSignal(SUCCESS, NOW).withSuccesses(1));
    }

    @Test
    void theSuccessThatReachesTheRecoveryCountGivesHealthyAndResetsTheBackOffStep() {
        ProviderHealth next = enforce.apply(row(DEGRADED).withSuccesses(1).withCoolDown(null, 3),
                new SendSignal(SUCCESS), NOW);

        assertThat(next).isEqualTo(row(HEALTHY, NOW).withLastSignal(SUCCESS, NOW).withSuccesses(2));
    }

    @Test
    void throttlingWhileDegradedResetsSuccessesAndLeavesTheOutageRunAlone() {
        ProviderHealth start = row(DEGRADED).withOutageRun(2, T0).withSuccesses(1);

        assertThat(enforce.apply(start, new SendSignal(THROTTLED), NOW))
                .isEqualTo(start.withLastSignal(THROTTLED, NOW).withSuccesses(0));
    }

    @Test
    void anOutageWhileDegradedWithNoRunOpenStartsOne() {
        ProviderHealth next = enforce.apply(row(DEGRADED).withSuccesses(1), new SendSignal(OUTAGE), NOW);

        assertThat(next).isEqualTo(row(DEGRADED).withLastSignal(OUTAGE, NOW).withOutageRun(1, NOW));
    }

    @Test
    void anOutageAfterTheWindowRestartsTheRunInsteadOfTripping() {
        Instant firstAt = NOW.minus(Duration.ofMinutes(2));

        ProviderHealth next = enforce.apply(row(DEGRADED).withOutageRun(2, firstAt), new SendSignal(OUTAGE), NOW);

        assertThat(next).isEqualTo(row(DEGRADED).withLastSignal(OUTAGE, NOW).withOutageRun(1, NOW));
    }

    @Test
    void anOutageInsideTheWindowCountsAndTheThresholdTrips() {
        Instant firstAt = NOW.minus(Duration.ofMinutes(2)).plusMillis(1);
        ProviderHealth second = enforce.apply(row(DEGRADED).withOutageRun(1, firstAt), new SendSignal(OUTAGE), NOW);
        assertThat(second).isEqualTo(row(DEGRADED).withLastSignal(OUTAGE, NOW).withOutageRun(2, firstAt));

        ProviderHealth third = enforce.apply(second, new SendSignal(OUTAGE), NOW);

        assertThat(third).isEqualTo(row(UNAVAILABLE, NOW).withLastSignal(OUTAGE, NOW).withOutageRun(3, firstAt)
                .withCoolDown(NOW.plusSeconds(30), 1));
    }

    @Test
    void aSuccessBetweenOutagesKeepsTheStateFromTripping() {
        ProviderHealth current = row(HEALTHY);
        for (ProviderSignalKind kind : List.of(OUTAGE, OUTAGE, SUCCESS, OUTAGE, OUTAGE)) {
            current = enforce.apply(current, new SendSignal(kind), NOW);
        }

        assertThat(current.state()).isEqualTo(DEGRADED);
        assertThat(current.consecutiveOutageSignals()).isEqualTo(2);
    }

    @ParameterizedTest
    @EnumSource(value = ProviderHealthState.class, names = {"HEALTHY", "DEGRADED"})
    void anOutageThresholdOfOneTripsOnTheFirstOutage(ProviderHealthState from) {
        ProviderHealthPolicy strict = new ProviderHealthPolicy(settings(Mode.ENFORCE, 1, 2));

        ProviderHealth next = strict.apply(row(from), new SendSignal(OUTAGE), NOW);

        assertThat(next.state()).isEqualTo(UNAVAILABLE);
        assertThat(next.coolDownUntil()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void aRecoveryCountOfOneGivesHealthyOnTheFirstSuccessOrPassedProbe() {
        ProviderHealthPolicy eager = new ProviderHealthPolicy(settings(Mode.ENFORCE, 3, 1));

        assertThat(eager.apply(row(DEGRADED), new SendSignal(SUCCESS), NOW).state()).isEqualTo(HEALTHY);
        assertThat(eager.apply(unavailable(2), new ProbeFinished(ProviderProbeResult.PASSED), NOW))
                .isEqualTo(row(HEALTHY, NOW).withLastSignal(SUCCESS, NOW));
    }

    @Test
    void anyNumberOfThrottledSignalsNeverGivesUnavailable() {
        ProviderHealth current = row(HEALTHY);
        for (int signal = 0; signal < 50; signal++) {
            current = enforce.apply(current, new SendSignal(THROTTLED), NOW.plusSeconds(signal));
            assertThat(current.state()).isEqualTo(DEGRADED);
        }
        assertThat(current.consecutiveOutageSignals()).isZero();
    }

    // --- send signals while the state is closed ----------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"UNAVAILABLE,SUCCESS", "UNAVAILABLE,THROTTLED", "UNAVAILABLE,OUTAGE", "UNKNOWN,SUCCESS",
            "UNKNOWN,THROTTLED", "UNKNOWN,OUTAGE"})
    void inEnforceModeASendSignalNeverLeavesAClosedState(ProviderHealthState from, ProviderSignalKind kind) {
        ProviderHealth start = from == UNAVAILABLE ? unavailable(2) : row(UNKNOWN);

        assertThat(enforce.apply(start, new SendSignal(kind), NOW)).isEqualTo(start.withLastSignal(kind, NOW));
    }

    @ParameterizedTest
    @CsvSource({"UNAVAILABLE,SUCCESS,PASSED", "UNAVAILABLE,THROTTLED,PASSED_THROTTLED", "UNAVAILABLE,OUTAGE,FAILED",
            "UNKNOWN,SUCCESS,PASSED", "UNKNOWN,THROTTLED,PASSED_THROTTLED", "UNKNOWN,OUTAGE,FAILED"})
    void inObserveModeASendSignalStandsInForTheProbe(ProviderHealthState from, ProviderSignalKind kind,
            ProviderProbeResult standsFor) {
        ProviderHealth start = from == UNAVAILABLE ? unavailable(2) : row(UNKNOWN);

        assertThat(observe.apply(start, new SendSignal(kind), NOW))
                .isEqualTo(observe.apply(start, new ProbeFinished(standsFor), NOW));
        assertThat(observe.apply(start, new SendSignal(kind), NOW).state()).isNotEqualTo(UNKNOWN);
    }

    // --- probe results -------------------------------------------------------------------------------------------

    @Test
    void aPassedProbeFromUnavailableGivesDegradedAsTheFirstSuccessAndKeepsTheStep() {
        ProviderHealth next = enforce.apply(unavailable(2).withProbeLease(LEASE),
                new ProbeFinished(ProviderProbeResult.PASSED), NOW);

        assertThat(next).isEqualTo(row(DEGRADED, NOW).withLastSignal(SUCCESS, NOW).withSuccesses(1)
                .withCoolDown(null, 2));
    }

    @Test
    void aThrottledProbeFromUnavailableGivesDegradedWithNoSuccessCounted() {
        ProviderHealth next = enforce.apply(unavailable(2).withProbeLease(LEASE),
                new ProbeFinished(ProviderProbeResult.PASSED_THROTTLED), NOW);

        assertThat(next).isEqualTo(row(DEGRADED, NOW).withLastSignal(THROTTLED, NOW).withCoolDown(null, 2));
    }

    @Test
    void aFailedProbeFromUnavailableStartsALongerCoolDownAndKeepsTheStateChangeTime() {
        ProviderHealth start = unavailable(2).withProbeLease(LEASE);

        ProviderHealth next = enforce.apply(start, new ProbeFinished(ProviderProbeResult.FAILED), NOW);

        assertThat(next).isEqualTo(start.withProbeLease(null).withLastSignal(OUTAGE, NOW)
                .withCoolDown(NOW.plusSeconds(120), 3));
        assertThat(next.stateChangedAt()).isEqualTo(T0);
    }

    @Test
    void aPassedProbeFromUnknownGivesHealthyWithEverythingReset() {
        ProviderHealth start = row(UNKNOWN).withOutageRun(2, T0).withSuccesses(1).withCoolDown(null, 3)
                .withProbeLease(LEASE);

        assertThat(enforce.apply(start, new ProbeFinished(ProviderProbeResult.PASSED), NOW))
                .isEqualTo(row(HEALTHY, NOW).withLastSignal(SUCCESS, NOW));
    }

    @Test
    void aThrottledProbeFromUnknownGivesDegraded() {
        ProviderHealth next = enforce.apply(row(UNKNOWN).withProbeLease(LEASE),
                new ProbeFinished(ProviderProbeResult.PASSED_THROTTLED), NOW);

        assertThat(next).isEqualTo(row(DEGRADED, NOW).withLastSignal(THROTTLED, NOW));
    }

    @Test
    void aFailedProbeFromUnknownTripsWithTheStepItKept() {
        ProviderHealth next = enforce.apply(row(UNKNOWN).withCoolDown(null, 1).withProbeLease(LEASE),
                new ProbeFinished(ProviderProbeResult.FAILED), NOW);

        assertThat(next).isEqualTo(row(UNAVAILABLE, NOW).withLastSignal(OUTAGE, NOW)
                .withCoolDown(NOW.plusSeconds(60), 2));
    }

    @ParameterizedTest
    @CsvSource({"HEALTHY,PASSED", "HEALTHY,PASSED_THROTTLED", "HEALTHY,FAILED", "DEGRADED,PASSED",
            "DEGRADED,PASSED_THROTTLED", "DEGRADED,FAILED"})
    void aLateProbeResultNeverOverridesAnOpenState(ProviderHealthState from, ProviderProbeResult result) {
        ProviderHealth start = row(from).withOutageRun(1, T0).withProbeLease(LEASE);

        assertThat(enforce.apply(start, new ProbeFinished(result), NOW)).isEqualTo(start.withProbeLease(null));
    }

    @ParameterizedTest
    @EnumSource(ProviderHealthState.class)
    void aProbeWithNoResultOnlyReleasesTheLease(ProviderHealthState from) {
        ProviderHealth start = (from == UNAVAILABLE ? unavailable(2) : row(from)).withProbeLease(LEASE);

        assertThat(enforce.apply(start, new ProbeFinished(ProviderProbeResult.NO_RESULT), NOW))
                .isEqualTo(start.withProbeLease(null));
    }

    // --- cool down -----------------------------------------------------------------------------------------------

    @Test
    void repeatedFailedProbesBackOffFromThirtySecondsToTheFiveMinuteCap() {
        ProviderHealth current = row(UNKNOWN);
        List<Duration> lengths = new ArrayList<>();
        for (int probe = 0; probe < 6; probe++) {
            current = enforce.apply(current, new ProbeFinished(ProviderProbeResult.FAILED), NOW);
            lengths.add(Duration.between(NOW, current.coolDownUntil()));
        }

        assertThat(lengths).extracting(Duration::toSeconds).containsExactly(30L, 60L, 120L, 240L, 300L, 300L);
        assertThat(current.coolDownStep()).isEqualTo(4);
    }

    @Test
    void aTripRightAfterAPassedProbeUsesTheNextLengthNotTheFirst() {
        ProviderHealth tripped = enforce.apply(row(UNKNOWN), new ProbeFinished(ProviderProbeResult.FAILED), NOW);
        ProviderHealth current = enforce.apply(tripped, new ProbeFinished(ProviderProbeResult.PASSED), NOW);
        for (int outage = 0; outage < 3; outage++) {
            current = enforce.apply(current, new SendSignal(OUTAGE), NOW);
        }

        assertThat(current.state()).isEqualTo(UNAVAILABLE);
        assertThat(Duration.between(NOW, current.coolDownUntil())).isEqualTo(Duration.ofSeconds(60));
    }

    // --- staleness and the reader's view -------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ProviderHealthState.class, names = {"HEALTHY", "DEGRADED"})
    void anOpenRowWithNoSignalForTheStalePeriodReadsAsUnknown(ProviderHealthState state) {
        ProviderHealth quiet = row(state).withLastSignal(SUCCESS, NOW.minus(Duration.ofMinutes(15)));
        ProviderHealth recent = row(state).withLastSignal(SUCCESS, NOW.minus(Duration.ofMinutes(15)).plusMillis(1));

        assertThat(enforce.effectiveState(quiet, NOW)).isEqualTo(UNKNOWN);
        assertThat(enforce.effectiveState(recent, NOW)).isEqualTo(state);
        assertThat(enforce.apply(quiet.withOutageRun(1, T0).withCoolDown(null, 2), new WentStale(), NOW))
                .isEqualTo(quiet.withOutageRun(1, T0).withCoolDown(null, 2).withState(UNKNOWN, NOW));
        assertThat(enforce.apply(recent, new WentStale(), NOW)).isEqualTo(recent);
    }

    @Test
    void anOpenRowThatNeverSawASignalAgesFromItsStateChange() {
        assertThat(enforce.effectiveState(row(HEALTHY), T0.plus(Duration.ofMinutes(15)))).isEqualTo(UNKNOWN);
        assertThat(enforce.effectiveState(row(HEALTHY), T0.plus(Duration.ofMinutes(14)))).isEqualTo(HEALTHY);
    }

    @Test
    void aClosedRowIsNeverStale() {
        Instant muchLater = NOW.plus(Duration.ofDays(2));

        assertThat(enforce.effectiveState(unavailable(1), muchLater)).isEqualTo(UNAVAILABLE);
        assertThat(enforce.apply(unavailable(1), new WentStale(), muchLater)).isEqualTo(unavailable(1));
    }

    // --- decision ------------------------------------------------------------------------------------------------

    @Test
    void aFreshOpenStateAllowsTheSend() {
        assertThat(enforce.decide(row(HEALTHY).withLastSignal(SUCCESS, NOW), NOW)).isEqualTo(new Decision.Allow());
        assertThat(enforce.decide(row(DEGRADED).withLastSignal(OUTAGE, NOW), NOW)).isEqualTo(new Decision.Allow());
    }

    @Test
    void unavailableInsideTheCoolDownRefusesWithTheTimeLeftRoundedUp() {
        ProviderHealth cooling = unavailable(1).withCoolDown(NOW.plusMillis(12_001), 1);

        assertThat(enforce.decide(cooling, NOW))
                .isEqualTo(new Decision.Refuse(RefusalReason.COOL_DOWN, Duration.ofSeconds(13)));
        assertThat(enforce.decide(cooling, NOW.plusMillis(12_000)))
                .isEqualTo(new Decision.Refuse(RefusalReason.COOL_DOWN, Duration.ofSeconds(1)));
    }

    @Test
    void unknownStaleOrCooledDownAsksForAProbe() {
        ProviderHealth cooledDown = unavailable(1).withCoolDown(NOW, 1);
        ProviderHealth quiet = row(HEALTHY).withLastSignal(SUCCESS, NOW.minus(Duration.ofMinutes(15)));

        assertThat(List.of(enforce.decide(row(UNKNOWN), NOW), enforce.decide(cooledDown, NOW),
                enforce.decide(quiet, NOW))).containsOnly(new Decision.Probe());
    }

    @Test
    void aLiveLeaseRefusesWithTheLeaseTimeLeftAndAnExpiredOneCanBeTakenAgain() {
        ProviderHealth probing = row(UNKNOWN).withProbeLease(LEASE);

        assertThat(enforce.decide(probing, NOW))
                .isEqualTo(new Decision.Refuse(RefusalReason.PROBE_IN_FLIGHT, Duration.ofSeconds(8)));
        assertThat(enforce.decide(probing, LEASE)).isEqualTo(new Decision.Probe());
    }

    // --- settings ------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"0,PT2M,2,PT15M,PT30S,PT5M,PT10S,outage-threshold", "21,PT2M,2,PT15M,PT30S,PT5M,PT10S,outage-threshold",
            "3,PT9S,2,PT15M,PT30S,PT5M,PT10S,outage-window", "3,PT2M,0,PT15M,PT30S,PT5M,PT10S,recovery-successes",
            "3,PT2M,11,PT15M,PT30S,PT5M,PT10S,recovery-successes", "3,PT2M,2,PT59S,PT30S,PT5M,PT10S,stale-after",
            "3,PT30M,2,PT30M,PT30S,PT5M,PT10S,longer than outage-window",
            "3,PT2M,2,PT15M,PT6M,PT5M,PT10S,cool-down-initial", "3,PT2M,2,PT15M,PT0S,PT5M,PT10S,cool-down-initial",
            "3,PT2M,2,PT15M,PT30S,PT61M,PT10S,cool-down-max", "3,PT2M,2,PT15M,PT30S,PT5M,PT61S,probe-lease",
            "3,PT2M,2,PT15M,PT30S,PT5M,PT0S,probe-lease"})
    void settingsOutsideTheirBoundsAreRefused(int outageThreshold, Duration outageWindow, int recoverySuccesses,
            Duration staleAfter, Duration coolDownInitial, Duration coolDownMax, Duration probeLease, String named) {
        assertThatThrownBy(() -> new ProviderHealthSettings(Mode.ENFORCE, outageThreshold, outageWindow,
                recoverySuccesses, staleAfter, coolDownInitial, coolDownMax, probeLease))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(named);
    }

    private static ProviderHealthSettings settings(Mode mode, int outageThreshold, int recoverySuccesses) {
        return new ProviderHealthSettings(mode, outageThreshold, Duration.ofMinutes(2), recoverySuccesses,
                Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofSeconds(10));
    }

    private static ProviderHealth row(ProviderHealthState state) {
        return row(state, T0);
    }

    private static ProviderHealth row(ProviderHealthState state, Instant changedAt) {
        return new ProviderHealth(MessagingProviderKey.META_WHATSAPP, state, changedAt, null, null, 0, null, 0, null,
                0, null);
    }

    /** UNAVAILABLE since T0, tripped by a three outage run, with its cool down already over at NOW. */
    private static ProviderHealth unavailable(int step) {
        return row(UNAVAILABLE).withOutageRun(3, T0).withCoolDown(T0.plusSeconds(30), step);
    }
}
