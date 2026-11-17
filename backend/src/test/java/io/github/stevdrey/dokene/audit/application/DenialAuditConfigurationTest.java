// ABOUTME: Tests the denial audit bound's settings (spec 0020): defaults, startup refusal of every value outside its
// ABOUTME: range, and the metric names and tags the Micrometer telemetry publishes.
package io.github.stevdrey.dokene.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.audit.application.DenialAuditTelemetry.SummaryResult;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class DenialAuditConfigurationTest {
    private static final String PREFIX = "dokene.audit.denial-limit.";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DenialAuditConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC);

    /** covers: AC-15 */
    @Test
    void bindsTheDocumentedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(DenialAuditLimitProperties.class)).isEqualTo(new DenialAuditLimitProperties(
                    10, 60, Duration.ofMinutes(15), 50_000, Duration.ofMinutes(1)));
        });
    }

    /** covers: AC-15 */
    @ParameterizedTest
    @ValueSource(strings = {
            "rows-per-key=0", "rows-per-key=10001",
            "rows-per-identity=9", "rows-per-identity=100001",
            "window=PT59S", "window=PT24H1S",
            "max-tracked-keys=999", "max-tracked-keys=1000001",
            "sweep-interval=PT4S", "sweep-interval=PT15M1S"})
    void refusesToStartOnAValueOutsideItsRange(String setting) {
        runner.withPropertyValues(PREFIX + setting).run(context -> assertThat(context).hasFailed());
    }

    /** covers: AC-15 */
    @Test
    void acceptsTheEdgesOfEveryRange() {
        runner.withPropertyValues(PREFIX + "rows-per-key=1", PREFIX + "rows-per-identity=1", PREFIX + "window=PT1M",
                PREFIX + "max-tracked-keys=1000", PREFIX + "sweep-interval=PT1M")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(PREFIX + "rows-per-key=10000", PREFIX + "rows-per-identity=100000",
                PREFIX + "window=PT24H", PREFIX + "max-tracked-keys=1000000", PREFIX + "sweep-interval=PT5S")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** covers: AC-13 */
    @Test
    void publishesCountersTaggedWithClosedNamesAndTheTrackedKeysGauge() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            DenialAuditTelemetry telemetry = context.getBean(DenialAuditTelemetry.class);
            DenialAuditBudget budget = context.getBean(DenialAuditBudget.class);

            telemetry.suppressed(AuditDenialReason.NO_TENANT_CONTEXT, DenialAuditBudget.Cause.KEY_BUDGET);
            telemetry.suppressed(AuditDenialReason.NO_TENANT_CONTEXT, DenialAuditBudget.Cause.KEY_BUDGET);
            telemetry.suppressed(AuditDenialReason.INSUFFICIENT_PERMISSION, DenialAuditBudget.Cause.TRACKER_FULL);
            telemetry.summary(SummaryResult.WRITTEN);
            telemetry.summary(SummaryResult.RETRY);
            telemetry.summary(SummaryResult.REJECTED);
            budget.decide(null, new IdentityId(UUID.randomUUID()), AuditDenialReason.NO_TENANT_CONTEXT,
                    TenantPermission.TENANT_READ, UUID.randomUUID());

            assertThat(registry.get("dokene.audit.denials.suppressed")
                    .tags("reason", "NO_TENANT_CONTEXT", "cause", "KEY_BUDGET").counter().count()).isEqualTo(2);
            assertThat(registry.get("dokene.audit.denials.suppressed")
                    .tags("reason", "INSUFFICIENT_PERMISSION", "cause", "TRACKER_FULL").counter().count())
                    .isEqualTo(1);
            for (String result : new String[] {"written", "retry", "rejected"}) {
                assertThat(registry.get("dokene.audit.denials.summaries").tag("result", result).counter().count())
                        .isEqualTo(1);
            }
            assertThat(registry.get("dokene.audit.denials.tracked.keys").gauge().value()).isEqualTo(1);
        });
    }

    /** covers: AC-13 */
    @Test
    void worksWithoutAMeterRegistry() {
        runner.run(context -> {
            DenialAuditTelemetry telemetry = context.getBean(DenialAuditTelemetry.class);
            telemetry.suppressed(AuditDenialReason.NO_TENANT_CONTEXT, DenialAuditBudget.Cause.KEY_BUDGET);
            telemetry.summary(SummaryResult.WRITTEN);
            assertThat(telemetry).isNotInstanceOf(MicrometerDenialAuditTelemetry.class);
        });
    }
}
