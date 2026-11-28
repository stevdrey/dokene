// ABOUTME: The admission counters and denial log line (ADR 0040, spec 0024 AC-13): the exact tag sets, a line with
// ABOUTME: the kind and reason and no identifier, and at most one line per kind and reason every ten seconds.
package io.github.stevdrey.dokene.admission;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.stevdrey.dokene.admission.AdmissionDecision.Admitted;
import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AdmissionTelemetryTest {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-27T12:00:00Z"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AdmissionTelemetry telemetry = new AdmissionTelemetry(meters, clock);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(AdmissionTelemetry.class);
    private Level previousLevel;

    @BeforeEach
    void captureTheLog() {
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        logs.start();
        logger.addAppender(logs);
        logger.setAdditive(false);
    }

    @AfterEach
    void releaseTheLog() {
        logger.detachAppender(logs);
        logger.setAdditive(true);
        logger.setLevel(previousLevel);
    }

    @Test
    void everyDecisionIsCountedWithKindOutcomeAndReasonOnly() {
        telemetry.decided(AdmissionKind.SEND, new Admitted());
        telemetry.decided(AdmissionKind.CANDIDATE, denied(AdmissionDenialReason.CANDIDATE_DAY));
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.GLOBAL_MINUTE));
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.GLOBAL_MINUTE));

        assertThat(count("SEND", "ADMITTED", "NONE")).isEqualTo(1.0);
        assertThat(count("CANDIDATE", "DENIED", "CANDIDATE_DAY")).isEqualTo(1.0);
        assertThat(count("SEND", "DENIED", "GLOBAL_MINUTE")).isEqualTo(2.0);
        assertThat(meters.getMeters()).allSatisfy(meter -> assertThat(tagKeys(meter))
                .containsExactlyInAnyOrder("kind", "outcome", "reason"));
    }

    @Test
    void aDenialWritesOneLineWithTheKindAndTheReason() {
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.TENANT_MINUTE));

        assertThat(lines()).containsExactly("Admission denied; kind=SEND, reason=TENANT_MINUTE, suppressed=0");
    }

    @Test
    void anAdmissionWritesNoLine() {
        telemetry.decided(AdmissionKind.SEND, new Admitted());

        assertThat(lines()).isEmpty();
    }

    @Test
    void repeatedDenialsWriteOneLinePerTenSecondsAndTheNextLineSaysHowManyWereLeftOut() {
        for (int repeat = 0; repeat < 4; repeat++) {
            telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.TENANT_MINUTE));
        }
        clock.advance(Duration.ofSeconds(10).minusMillis(1));
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.TENANT_MINUTE));
        assertThat(lines()).hasSize(1);

        clock.advance(Duration.ofMillis(1));
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.TENANT_MINUTE));
        clock.advance(Duration.ofSeconds(10));
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.TENANT_MINUTE));

        assertThat(lines()).containsExactly("Admission denied; kind=SEND, reason=TENANT_MINUTE, suppressed=0",
                "Admission denied; kind=SEND, reason=TENANT_MINUTE, suppressed=4",
                "Admission denied; kind=SEND, reason=TENANT_MINUTE, suppressed=0");
        assertThat(count("SEND", "DENIED", "TENANT_MINUTE")).isEqualTo(7.0);
    }

    @Test
    void anotherKindOrReasonIsNotHeldBackByTheFirst() {
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.TENANT_MINUTE));
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.TENANT_DAY));
        telemetry.decided(AdmissionKind.CANDIDATE, denied(AdmissionDenialReason.STATE_UNAVAILABLE));
        telemetry.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.STATE_UNAVAILABLE));

        assertThat(lines()).hasSize(4);
    }

    @Test
    void aFailedThrottleSignalIsCountedByOperationAndLoggedWithoutAnyIdentifier() {
        telemetry.throttleSignalFailed("CannotAcquireLockException");

        assertThat(meters.counter(AdmissionTelemetry.STORE_FAILURES, "operation", "THROTTLE_SIGNAL").count())
                .isEqualTo(1.0);
        assertThat(lines()).containsExactly(
                "Admission throttle signal not recorded; errorType=CannotAcquireLockException");
    }

    @Test
    void withoutAMeterRegistryTheLogLineIsStillWritten() {
        AdmissionTelemetry unmetered = new AdmissionTelemetry(null, clock);

        unmetered.decided(AdmissionKind.SEND, new Admitted());
        unmetered.decided(AdmissionKind.SEND, denied(AdmissionDenialReason.SWITCH_OFF));
        unmetered.throttleSignalFailed("TenantMismatch");

        assertThat(lines()).hasSize(2);
    }

    private static Denied denied(AdmissionDenialReason reason) {
        return new Denied(reason, Duration.ofSeconds(1));
    }

    private double count(String kind, String outcome, String reason) {
        return meters.counter(AdmissionTelemetry.DECISIONS, "kind", kind, "outcome", outcome, "reason", reason)
                .count();
    }

    private static List<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream().map(Tag::getKey).toList();
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
