// ABOUTME: Wires the denial audit bound (spec 0020): binds and validates dokene.audit.denial-limit, and declares the
// ABOUTME: one in-memory budget and its telemetry. There is no setting that turns the bound off.
package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DenialAuditLimitProperties.class)
class DenialAuditConfiguration {

    @Bean
    DenialAuditBudget denialAuditBudget(DenialAuditLimitProperties limits, Clock clock) {
        return new DenialAuditBudget(limits, clock);
    }

    @Bean
    DenialAuditTelemetry denialAuditTelemetry(ObjectProvider<MeterRegistry> registry, DenialAuditBudget budget) {
        MeterRegistry meterRegistry = registry.getIfAvailable();
        if (meterRegistry != null) {
            return new MicrometerDenialAuditTelemetry(meterRegistry, budget);
        }
        return new DenialAuditTelemetry() {
            @Override
            public void suppressed(AuditDenialReason reason, DenialAuditBudget.Cause cause) {
            }

            @Override
            public void summary(SummaryResult result) {
            }
        };
    }
}
