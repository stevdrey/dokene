// ABOUTME: Counters and the denial log line of the admission budget (ADR 0040): dokene.admission.decisions tagged only
// ABOUTME: with kind, outcome and reason, and dokene.admission.store.failures. No tenant, message or customer value.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class AdmissionTelemetry {
    static final String DECISIONS = "dokene.admission.decisions";
    static final String STORE_FAILURES = "dokene.admission.store.failures";
    static final String THROTTLE_SIGNAL = "THROTTLE_SIGNAL";
    /** A caller in a loop would otherwise write one line per denial. */
    static final Duration DENIAL_LOG_INTERVAL = Duration.ofSeconds(10);
    private static final Logger log = LoggerFactory.getLogger(AdmissionTelemetry.class);

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<DenialKey, DenialLine> denialLines = new ConcurrentHashMap<>();

    /** {@code registry} may be null: the counters are then skipped and the log line is still written. */
    AdmissionTelemetry(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    private record DenialKey(AdmissionKind kind, AdmissionDenialReason reason) {
    }

    private record DenialLine(Instant loggedAt, long suppressed) {
    }

    void decided(AdmissionKind kind, AdmissionDecision decision) {
        switch (decision) {
            case AdmissionDecision.Admitted _ -> count(kind, "ADMITTED", "NONE");
            case Denied denied -> {
                count(kind, "DENIED", denied.reason().name());
                logDenial(kind, denied.reason());
            }
        }
    }

    /** The throttle signal could not be stored, or was given for a tenant that is not the one in context. */
    void throttleSignalFailed(String errorType) {
        if (registry != null) {
            registry.counter(STORE_FAILURES, "operation", THROTTLE_SIGNAL).increment();
        }
        log.warn("Admission throttle signal not recorded; errorType={}", errorType);
    }

    private void count(AdmissionKind kind, String outcome, String reason) {
        if (registry != null) {
            registry.counter(DECISIONS, "kind", kind.name(), "outcome", outcome, "reason", reason).increment();
        }
    }

    /** At most one line per kind and reason every ten seconds on this instance; the next line says how many it stands for. */
    private void logDenial(AdmissionKind kind, AdmissionDenialReason reason) {
        Instant now = clock.instant();
        long[] suppressedBefore = {-1};
        denialLines.compute(new DenialKey(kind, reason), (key, last) -> {
            if (last != null && now.isBefore(last.loggedAt().plus(DENIAL_LOG_INTERVAL))) {
                return new DenialLine(last.loggedAt(), last.suppressed() + 1);
            }
            suppressedBefore[0] = last == null ? 0 : last.suppressed();
            return new DenialLine(now, 0);
        });
        if (suppressedBefore[0] >= 0) {
            log.info("Admission denied; kind={}, reason={}, suppressed={}", kind, reason, suppressedBefore[0]);
        }
    }
}
