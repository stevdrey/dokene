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
 * <p>The check and the write are not one transaction: requests already in flight when the limit is reached may
 * still complete. The overshoot is bounded by the AI rate limit, the retry attempts and the per-request
 * {@code max_output_tokens} cap.
 */
@Component
@EnableConfigurationProperties(AiTokenBudgetProperties.class)
public class AiTokenBudget {
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
        return new Meter(tenantId, period);
    }

    /** Usage of one invocation, bound to one tenant and one month. Not shared between invocations. */
    public final class Meter {
        private final TenantId tenantId;
        private final LocalDate period;
        private final AtomicLong inputTokens = new AtomicLong();
        private final AtomicLong outputTokens = new AtomicLong();

        private Meter(TenantId tenantId, LocalDate period) {
            this.tenantId = tenantId;
            this.period = period;
        }

        /** Wraps the raw provider so that the usage reported by each attempt is counted. */
        public AiProvider wrap(AiProvider delegate) {
            return new MeteredProvider(Objects.requireNonNull(delegate, "Delegate provider is required"), this);
        }

        /**
         * Runs the provider call and then writes the counted usage. A write failure propagates (a database outage
         * fails the request, ADR 0029) unless the call itself failed, whose exception then wins and carries the
         * write failure as suppressed.
         */
        public <R> R call(Supplier<R> invocation) {
            Objects.requireNonNull(invocation, "Invocation is required");
            RuntimeException primary = null;
            try {
                return invocation.get();
            } catch (RuntimeException failure) {
                primary = failure;
                throw failure;
            } finally {
                try {
                    flush();
                } catch (RuntimeException writeFailure) {
                    if (primary == null) {
                        throw writeFailure;
                    }
                    primary.addSuppressed(writeFailure);
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
