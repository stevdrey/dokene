package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiResilience;
import io.github.stevdrey.dokene.ai.application.AiRetryProperties;
import io.github.stevdrey.dokene.ai.application.AiTelemetry;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.application.RetryGuard;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** ADR 0035: the order rate limit, budget, metered retried call, and the disabled-provider exemption. */
class AiInvocationRunnerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate PERIOD = LocalDate.of(2026, 11, 1);

    private final CountingUsage usage = new CountingUsage();
    private final AiTokenBudget budget = new AiTokenBudget(usage, new AiTokenBudgetProperties(100L), CLOCK);
    private final TenantContext tenant = new TenantContext(new TenantId(UUID.randomUUID()),
            new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()), TenantRole.OPERATOR);
    private final AiRecommendationRequest request = new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION,
            mock(RecommendationContext.class), Duration.ofSeconds(2));

    @Test
    void anExhaustedTenantIsStillRateLimitedSoItsRefusalsAreThrottled() {
        usage.add(tenant.tenantId(), PERIOD, 100, 0);
        var limiter = new DefaultFollowUpRecommendationRateLimiter(60, 2, Duration.ofSeconds(5), CLOCK);
        ScriptedProvider provider = new ScriptedProvider(true);
        AiInvocationRunner runner = new AiInvocationRunner(provider, null, limiter, budget);

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> runner.recommend(tenant, RetryGuard.none(), request))
                    .isInstanceOf(AiTokenBudgetExhaustedException.class);
        }
        assertThatThrownBy(() -> runner.recommend(tenant, RetryGuard.none(), request))
                .isInstanceOf(RecommendationRateLimitExceededException.class);
        assertThat(provider.calls).hasValue(0);
    }

    @Test
    void aDisabledProviderSkipsTheBudgetAndKeepsReportingNotAvailable() {
        usage.add(tenant.tenantId(), PERIOD, 500, 500);
        usage.reads.set(0);
        ScriptedProvider disabled = new ScriptedProvider(false);
        AiInvocationRunner runner = new AiInvocationRunner(disabled, null, null, budget);

        assertThatThrownBy(() -> runner.recommend(tenant, RetryGuard.none(), request))
                .isInstanceOf(AiProviderException.class)
                .extracting(failure -> ((AiProviderException) failure).category())
                .isEqualTo(AiFailureCategory.NOT_AVAILABLE);
        assertThat(usage.reads).hasValue(0);
    }

    @Test
    void theMeterSitsInsideTheRetryDecoratorSoEveryAttemptCounts() {
        ScriptedProvider provider = new ScriptedProvider(true);
        AiResilience resilience = new AiResilience(new AiRetryProperties(2, Duration.ofMillis(1), Duration.ofMillis(1),
                Duration.ofMillis(1)), AiTelemetry.noop());
        AiInvocationRunner runner = new AiInvocationRunner(provider, resilience, null, budget);

        AiRecommendationResponse response = runner.recommend(tenant, RetryGuard.none(), request);

        assertThat(response).isNotNull();
        assertThat(provider.calls).hasValue(2);
        // the throttled first attempt (30 + 10) and the successful retry (5 + 5)
        assertThat(usage.used(tenant.tenantId(), PERIOD)).isEqualTo(50);
    }

    @Test
    void withoutATenantContextNeitherTheRateLimitNorTheBudgetApplies() {
        ScriptedProvider provider = new ScriptedProvider(true);
        var limiter = new DefaultFollowUpRecommendationRateLimiter(1, 1, Duration.ofSeconds(5), CLOCK);
        AiInvocationRunner runner = new AiInvocationRunner(provider, null, limiter, budget);

        assertThatThrownBy(() -> runner.recommend(null, RetryGuard.none(), request))
                .isInstanceOf(AiProviderException.class);
        assertThat(usage.reads).hasValue(0);
    }

    /** First attempt: THROTTLED with usage 30/10; then success with usage 5/5 (or NOT_AVAILABLE when disabled). */
    private static final class ScriptedProvider implements AiProvider {
        private final boolean enabled;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedProvider(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public AiRecommendationResponse recommend(AiRecommendationRequest request) {
            int call = calls.incrementAndGet();
            if (!enabled) {
                throw new AiProviderException(AiFailureCategory.NOT_AVAILABLE,
                        metadata(new AiTokenUsage(0, 0), AiCompletionStatus.FAILED));
            }
            if (call == 1) {
                throw new AiProviderException(AiFailureCategory.THROTTLED,
                        metadata(new AiTokenUsage(30, 10), AiCompletionStatus.FAILED));
            }
            return new AiRecommendationResponse(new NoRecommendation(NoRecommendationReason.INSUFFICIENT_HISTORY,
                    "Sin historial", RecommendationConfidence.of(0.5)),
                    metadata(new AiTokenUsage(5, 5), AiCompletionStatus.SUCCEEDED));
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        private static AiInvocationMetadata metadata(AiTokenUsage usage, AiCompletionStatus status) {
            return new AiInvocationMetadata("scripted", "scripted-model", null, Duration.ofMillis(1), usage, status);
        }
    }

    private static final class CountingUsage implements AiTokenUsageRepository {
        private final Map<String, Long> used = new HashMap<>();
        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public long usedTokens(TenantId tenantId, LocalDate periodStart) {
            reads.incrementAndGet();
            return used(tenantId, periodStart);
        }

        @Override
        public void add(TenantId tenantId, LocalDate periodStart, long inputTokens, long outputTokens) {
            used.merge(tenantId.value() + "/" + periodStart, inputTokens + outputTokens, Long::sum);
        }

        long used(TenantId tenantId, LocalDate periodStart) {
            return used.getOrDefault(tenantId.value() + "/" + periodStart, 0L);
        }
    }
}
