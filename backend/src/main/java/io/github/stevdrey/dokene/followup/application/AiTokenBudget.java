package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiTokenUsage;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Per-tenant monthly AI token budget (ADR 0035).
 *
 * <p>{@link #open(TenantId)} runs before any provider call and refuses the invocation once the tenant's usage for the
 * current UTC calendar month has reached the limit, so an exhausted tenant makes no provider call. The returned
 * {@link Meter} wraps the raw provider <em>inside</em> the retry decorator, so the usage the provider reports for
 * every attempt is counted: successes, failures, incomplete responses and retries alike, because the provider bills
 * them all. Usage is accumulated in memory during the invocation and written once afterwards, so the retry loop never
 * waits on the database and a database error can never be mistaken for a retryable provider failure.
 *
 * <p>The limit is an admission threshold on recorded usage, not a ceiling (ADR 0035 §6): the check and the write
 * are not one transaction, so invocations admitted below the threshold run to completion and can push the recorded
 * total past it, and attempts that report no usage are not counted.
 */
@Component
@EnableConfigurationProperties(AiTokenBudgetProperties.class)
public class AiTokenBudget {
    private static final Logger log = LoggerFactory.getLogger(AiTokenBudget.class);
    private static final Meter UNMETERED = new Meter(null, null, null);

    private final AiTokenUsageRepository usage;
    private final long monthlyTokens;
    private final Clock clock;

    public AiTokenBudget(AiTokenUsageRepository usage, AiTokenBudgetProperties properties, Clock clock) {
        this.usage = Objects.requireNonNull(usage, "Token usage repository is required");
        this.monthlyTokens = Objects.requireNonNull(properties, "Token budget properties are required")
                .monthlyTokens();
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /**
     * Starts one metered invocation for {@code tenantId}, or throws {@link AiTokenBudgetExhaustedException} when the
     * tenant has no budget left this month. The month is fixed here, so an invocation that spans midnight at the end
     * of a month is charged to the month it started in.
     */
    public Meter open(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        LocalDate period = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).withDayOfMonth(1);
        if (usage.usedTokens(tenantId, period) >= monthlyTokens) {
            throw new AiTokenBudgetExhaustedException();
        }
        return new Meter(usage, tenantId, period);
    }

    /**
     * A meter that counts and writes nothing: for callers without a tenant context or budget, and for a disabled
     * provider, which bills nothing. {@link Meter#wrap} returns the delegate and {@link Meter#call} just runs it.
     */
    public static Meter unmetered() {
        return UNMETERED;
    }

    /** Usage of one invocation, bound to one tenant and one month. Not shared between invocations. */
    public static final class Meter {
        private final AiTokenUsageRepository usage;
        private final TenantId tenantId;
        private final LocalDate period;
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();

        private Meter(AiTokenUsageRepository usage, TenantId tenantId, LocalDate period) {
            this.usage = usage;
            this.tenantId = tenantId;
            this.period = period;
        }

        /** Wraps the raw provider so that the usage reported by each attempt is counted. */
        public AiProvider wrap(AiProvider delegate) {
            Objects.requireNonNull(delegate, "Delegate provider is required");
            return usage == null ? delegate : new MeteredProvider(delegate, this);
        }

        /**
         * Runs the provider call and then writes the counted usage. A write failure propagates (a database outage
         * fails the request, ADR 0029) unless the call itself failed, whose exception then wins and carries the
         * write failure as suppressed.
         */
        public <R> R call(Supplier<R> invocation) {
            Objects.requireNonNull(invocation, "Invocation is required");
            if (usage == null) {
                return invocation.get();
            }
            RuntimeException primary = null;
            try {
                return invocation.get();
            } catch (RuntimeException failure) {
                primary = failure;
                throw failure;
            } finally {
                long lostInput = inputTokens.get();
                long lostOutput = outputTokens.get();
                try {
                    flush();
                } catch (RuntimeException writeFailure) {
                    if (primary == null) {
                        throw writeFailure;
                    }
                    primary.addSuppressed(writeFailure);
                    // Billed tokens the budget did not record: counts and the failure type only, never tenant or
                    // customer data (same rule as AiOutcomeReporter).
                    log.warn("AI token usage could not be recorded: inputTokens={}, outputTokens={}, failure={}",
                            lostInput, lostOutput, writeFailure.getClass().getSimpleName());
                }
            }
        }

        long inputTokens() {
            return inputTokens.get();
        }

        long outputTokens() {
            return outputTokens.get();
        }

        private void record(AiInvocationMetadata metadata) {
            AiTokenUsage reported = metadata == null ? null : metadata.usage();
            if (reported != null) {
                inputTokens.addAndGet(reported.inputTokens());
                outputTokens.addAndGet(reported.outputTokens());
            }
        }

        private void flush() {
            long input = inputTokens.getAndSet(0);
            long output = outputTokens.getAndSet(0);
            if (input > 0 || output > 0) {
                usage.add(tenantId, period, input, output);
            }
        }
    }

    /** Counts usage per attempt and rethrows unchanged; normalization and retry stay with the outer decorator. */
    private record MeteredProvider(AiProvider delegate, Meter meter) implements AiProvider {
        @Override
        public AiRecommendationResponse recommend(AiRecommendationRequest request) {
            try {
                AiRecommendationResponse response = delegate.recommend(request);
                meter.record(response == null ? null : response.metadata());
                return response;
            } catch (AiProviderException failure) {
                meter.record(failure.metadata());
                throw failure;
            }
        }

        @Override
        public AiDraftResponse draft(AiDraftRequest request) {
            try {
                AiDraftResponse response = delegate.draft(request);
                meter.record(response == null ? null : response.metadata());
                return response;
            } catch (AiProviderException failure) {
                meter.record(failure.metadata());
                throw failure;
            }
        }

        @Override
        public Duration defaultTimeout() {
            return delegate.defaultTimeout();
        }

        @Override
        public Duration maxTimeout() {
            return delegate.maxTimeout();
        }
    }
}
