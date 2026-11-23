package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AiTokenBudgetTest {
    // 21:30 on 30 November in Guayaquil is already 1 December in UTC: the budget month follows UTC.
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T02:30:00Z"), ZoneId.of("America/Guayaquil"));
    private static final LocalDate PERIOD = LocalDate.of(2026, 12, 1);

    private final InMemoryUsage usage = new InMemoryUsage();
    private final AiTokenBudget budget = new AiTokenBudget(usage, new AiTokenBudgetProperties(100L), CLOCK);
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final TenantId otherTenant = new TenantId(UUID.randomUUID());

    @Test
    void refusesBeforeAnyProviderCallOnceTheMonthlyLimitIsReached() {
        usage.add(tenant, PERIOD, 60, 40);
        ScriptedProvider provider = new ScriptedProvider();

        assertThatThrownBy(() -> budget.open(tenant).wrap(provider).recommend(null))
                .isInstanceOf(AiTokenBudgetExhaustedException.class);
        assertThat(provider.calls).isZero();
    }

    @Test
    void allowsAnInvocationWhileBelowTheLimitAndUsesTheUtcCalendarMonth() {
        usage.add(tenant, PERIOD, 60, 39);
        // last month's usage does not count against this month
        usage.add(tenant, PERIOD.minusMonths(1), 1_000, 1_000);

        assertThat(budget.open(tenant)).isNotNull();
    }

    @Test
    void theBudgetOfOneTenantNeverAffectsAnother() {
        usage.add(tenant, PERIOD, 100, 0);

        assertThatThrownBy(() -> budget.open(tenant)).isInstanceOf(AiTokenBudgetExhaustedException.class);
        AiTokenBudget.Meter meter = budget.open(otherTenant);
        ScriptedProvider provider = new ScriptedProvider().succeed(new AiTokenUsage(3, 2));
        meter.call(() -> meter.wrap(provider).recommend(null));

        assertThat(usage.used(otherTenant, PERIOD)).isEqualTo(5);
        assertThat(usage.used(tenant, PERIOD)).isEqualTo(100);
    }

    @Test
    void countsTheReportedUsageOfFailedAndSuccessfulAttemptsAndWritesItOnce() {
        AiTokenBudget.Meter meter = budget.open(tenant);
        ScriptedProvider provider = new ScriptedProvider()
                .fail(new AiTokenUsage(30, 10))
                .fail(null)
                .succeed(new AiTokenUsage(5, 7));
        AiProvider metered = meter.wrap(provider);

        AiRecommendationResponse response = meter.call(() -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    metered.recommend(null);
                } catch (AiProviderException ignored) {
                    // the outer retry decorator would retry here
                }
            }
            return metered.recommend(null);
        });

        assertThat(response).isNotNull();
        assertThat(usage.writes).containsExactly(new Write(tenant, PERIOD, 35, 17));
        assertThat(meter.inputTokens()).isZero();
        assertThat(meter.outputTokens()).isZero();
    }

    @Test
    void anInvocationThatFailsStillRecordsWhatTheProviderBilled() {
        AiTokenBudget.Meter meter = budget.open(tenant);
        ScriptedProvider provider = new ScriptedProvider().fail(new AiTokenUsage(12, 0));

        assertThatThrownBy(() -> meter.call(() -> meter.wrap(provider).recommend(null)))
                .isInstanceOf(AiProviderException.class);
        assertThat(usage.used(tenant, PERIOD)).isEqualTo(12);
    }

    @Test
    void writesNothingWhenTheProviderReportedNoUsage() {
        AiTokenBudget.Meter meter = budget.open(tenant);
        ScriptedProvider provider = new ScriptedProvider().succeed(new AiTokenUsage(0, 0));

        meter.call(() -> meter.wrap(provider).recommend(null));

        assertThat(usage.writes).isEmpty();
    }

    @Test
    void aFailedWriteFailsASuccessfulInvocationButNeverMasksAProviderFailure() {
        usage.failWrites = true;
        AiTokenBudget.Meter succeeding = budget.open(tenant);
        ScriptedProvider ok = new ScriptedProvider().succeed(new AiTokenUsage(1, 1));
        assertThatThrownBy(() -> succeeding.call(() -> succeeding.wrap(ok).recommend(null)))
                .isInstanceOf(IllegalStateException.class);

        AiTokenBudget.Meter failing = budget.open(tenant);
        ScriptedProvider broken = new ScriptedProvider().fail(new AiTokenUsage(1, 1));
        assertThatThrownBy(() -> failing.call(() -> failing.wrap(broken).recommend(null)))
                .isInstanceOf(AiProviderException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .singleElement().isInstanceOf(IllegalStateException.class));
    }

    @Test
    void aSuppressedWriteFailureIsLoggedWithTheLostCountsOnly() {
        usage.failWrites = true;
        Logger logger = (Logger) LoggerFactory.getLogger(AiTokenBudget.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            AiTokenBudget.Meter meter = budget.open(tenant);
            ScriptedProvider broken = new ScriptedProvider().fail(new AiTokenUsage(9, 4));
            assertThatThrownBy(() -> meter.call(() -> meter.wrap(broken).recommend(null)))
                    .isInstanceOf(AiProviderException.class);
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("AI token usage could not be recorded: inputTokens=9, outputTokens=4, "
                            + "failure=IllegalStateException")
                    .doesNotContain(tenant.value().toString());
        });
    }

    @Test
    void theUnmeteredMeterIsTransparentAndWritesNothing() {
        ScriptedProvider provider = new ScriptedProvider().succeed(new AiTokenUsage(50, 50));
        AiTokenBudget.Meter meter = AiTokenBudget.unmetered();

        assertThat(meter.wrap(provider)).isSameAs(provider);
        assertThat(meter.call(() -> meter.wrap(provider).recommend(null))).isNotNull();
        assertThat(usage.writes).isEmpty();
    }

    @Test
    void theMeteredProviderKeepsTheDelegateTimeouts() {
        AiProvider metered = budget.open(tenant).wrap(new ScriptedProvider());

        assertThat(metered.defaultTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(metered.maxTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theLimitMustBePositiveAndDefaultsWhenAbsent() {
        assertThat(new AiTokenBudgetProperties(null).monthlyTokens())
                .isEqualTo(AiTokenBudgetProperties.DEFAULT_MONTHLY_TOKENS);
        assertThatThrownBy(() -> new AiTokenBudgetProperties(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiTokenBudgetProperties(-1L)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exhaustionIsAStableNonRetryableUnavailableReason() {
        assertThat(AiUnavailableReason.TOKEN_BUDGET_EXHAUSTED.retryable()).isFalse();
    }

    private record Write(TenantId tenant, LocalDate period, long input, long output) {
    }

    private static final class InMemoryUsage implements AiTokenUsageRepository {
        private final Map<String, Long> used = new HashMap<>();
        private final List<Write> writes = new ArrayList<>();
        private boolean failWrites;

        @Override
        public long usedTokens(TenantId tenantId, LocalDate periodStart) {
            return used(tenantId, periodStart);
        }

        @Override
        public void add(TenantId tenantId, LocalDate periodStart, long inputTokens, long outputTokens) {
            if (failWrites) {
                throw new IllegalStateException("database unavailable");
            }
            writes.add(new Write(tenantId, periodStart, inputTokens, outputTokens));
            used.merge(tenantId.value() + "/" + periodStart, inputTokens + outputTokens, Long::sum);
        }

        long used(TenantId tenantId, LocalDate periodStart) {
            return used.getOrDefault(tenantId.value() + "/" + periodStart, 0L);
        }
    }

    private static final class ScriptedProvider implements AiProvider {
        private final Deque<Supplier<AiRecommendationResponse>> script = new ArrayDeque<>();
        private int calls;

        ScriptedProvider succeed(AiTokenUsage usage) {
            script.add(() -> new AiRecommendationResponse(new NoRecommendation(NoRecommendationReason.INSUFFICIENT_HISTORY,
                    "Sin historial", RecommendationConfidence.of(0.5)), metadata(usage, AiCompletionStatus.SUCCEEDED)));
            return this;
        }

        ScriptedProvider fail(AiTokenUsage usage) {
            script.add(() -> {
                throw new AiProviderException(AiFailureCategory.THROTTLED, metadata(usage, AiCompletionStatus.FAILED));
            });
            return this;
        }

        @Override
        public AiRecommendationResponse recommend(AiRecommendationRequest request) {
            calls++;
            return script.removeFirst().get();
        }

        private static AiInvocationMetadata metadata(AiTokenUsage usage, AiCompletionStatus status) {
            return new AiInvocationMetadata("scripted", "scripted-model", null, Duration.ofMillis(1), usage, status);
        }
    }
}
