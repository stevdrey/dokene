// ABOUTME: Micrometer DenialAuditTelemetry: dokene.audit.denials.suppressed tagged with reason and cause,
// ABOUTME: dokene.audit.denials.summaries tagged with result, and the gauge dokene.audit.denials.tracked.keys.
package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

public final class MicrometerDenialAuditTelemetry implements DenialAuditTelemetry {
    static final String SUPPRESSED = "dokene.audit.denials.suppressed";
    static final String SUMMARIES = "dokene.audit.denials.summaries";
    static final String TRACKED_KEYS = "dokene.audit.denials.tracked.keys";

    private final MeterRegistry registry;

    public MicrometerDenialAuditTelemetry(MeterRegistry registry, DenialAuditBudget budget) {
        this.registry = Objects.requireNonNull(registry, "Meter registry is required");
        Gauge.builder(TRACKED_KEYS, Objects.requireNonNull(budget, "Budget is required"),
                DenialAuditBudget::trackedWindows).register(registry);
    }

    @Override
    public void suppressed(AuditDenialReason reason, DenialAuditBudget.Cause cause) {
        registry.counter(SUPPRESSED, "reason", reason.name(), "cause", cause.name()).increment();
    }

    @Override
    public void summary(SummaryResult result) {
        registry.counter(SUMMARIES, "result", result.name().toLowerCase(Locale.ROOT)).increment();
    }
}
