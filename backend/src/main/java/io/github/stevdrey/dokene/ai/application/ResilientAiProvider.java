package io.github.stevdrey.dokene.ai.application;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Domain-level decorator that adds bounded retry, telemetry and exception normalization around an
 * {@link AiProvider}. It owns the only retry loop (the SDK client is configured with zero retries).
 *
 * <p>Safety properties:
 * <ul>
 *   <li>Only {@link AiFailureCategory#retryable()} categories are retried; disabled/refused/invalid/rejected/
 *       cancelled outcomes never are.</li>
 *   <li>The requested timeout is the total deadline: each attempt receives only the remaining budget and no
 *       retry starts without {@link AiRetryProperties#minAttemptBudget()} left after backoff. The first attempt always
 *       runs with the requested timeout: the minimum budget only gates retries, so an explicit short client timeout
 *       is honored as given.</li>
 *   <li>It wraps only the provider call. Authorization revalidation, rate limiting and the Action Gate run in
 *       the caller, before and after it, so retry cannot bypass them.</li>
 *   <li>Raw provider exception messages are never copied: unexpected runtime failures become
 *       {@link AiFailureCategory#UNAVAILABLE}.</li>
 * </ul>
 */
public final class ResilientAiProvider implements AiProvider {
    private final AiProvider delegate;
    private final AiRetryProperties retry;
    private final AiTelemetry telemetry;
    private final Sleeper sleeper;
    private final LongSupplier nanoClock;
    private final DoubleSupplier jitter;

    public ResilientAiProvider(AiProvider delegate, AiRetryProperties retry, AiTelemetry telemetry) {
        this(delegate, retry, telemetry, Thread::sleep, System::nanoTime,
                () -> ThreadLocalRandom.current().nextDouble());
    }

    ResilientAiProvider(AiProvider delegate, AiRetryProperties retry, AiTelemetry telemetry, Sleeper sleeper,
            LongSupplier nanoClock, DoubleSupplier jitter) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate provider is required");
        this.retry = Objects.requireNonNull(retry, "Retry properties are required");
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
        this.sleeper = Objects.requireNonNull(sleeper, "Sleeper is required");
        this.nanoClock = Objects.requireNonNull(nanoClock, "Clock is required");
        this.jitter = Objects.requireNonNull(jitter, "Jitter is required");
    }

    @Override
    public AiRecommendationResponse recommend(AiRecommendationRequest request) {
        return execute(request.operation(), request.timeout(),
                remaining -> delegate.recommend(
                        new AiRecommendationRequest(request.operation(), request.context(), remaining)),
                AiRecommendationResponse::metadata);
    }

    @Override
    public AiDraftResponse draft(AiDraftRequest request) {
        return execute(request.operation(), request.timeout(),
                remaining -> delegate.draft(new AiDraftRequest(request.operation(), request.context(), remaining)),
                AiDraftResponse::metadata);
    }

    @Override
    public Duration defaultTimeout() {
        return delegate.defaultTimeout();
    }

    @Override
    public Duration maxTimeout() {
        return delegate.maxTimeout();
    }

    private <R> R execute(AiOperation operation, Duration budget, Function<Duration, R> call,
            Function<R, AiInvocationMetadata> metadataOf) {
        long startNanos = nanoClock.getAsLong();
        int attempt = 1;
        while (true) {
            long attemptStartNanos = nanoClock.getAsLong();
            Duration remaining = remaining(budget, startNanos);
            AiProviderException failure;
            try {
                R response = call.apply(remaining);
                if (response != null) {
                    telemetry.attemptCompleted(operation, metadataOf.apply(response), null);
                }
                return response;
            } catch (AiProviderException e) {
                failure = e;
            } catch (RuntimeException e) {
                // Deliberately drops the message/cause: provider and framework text is untrusted and may be sensitive.
                // An unsupported operation is permanent, so it must stay NOT_AVAILABLE (never retried).
                AiFailureCategory category = e instanceof UnsupportedOperationException
                        ? AiFailureCategory.NOT_AVAILABLE : AiFailureCategory.UNAVAILABLE;
                failure = new AiProviderException(category, new AiInvocationMetadata(
                        "unknown", null, null, Duration.ofNanos(Math.max(0, nanoClock.getAsLong() - attemptStartNanos)),
                        null, AiCompletionStatus.FAILED));
            }
            telemetry.attemptCompleted(operation, failure.metadata(), failure.category());

            Duration backoff = backoff(failure, attempt);
            if (!canRetry(failure, attempt, budget, startNanos, backoff)) {
                throw failure;
            }
            telemetry.retryScheduled(operation, failure.metadata().providerId(), failure.category());
            try {
                sleeper.sleep(backoff);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(
                        failure.metadata().providerId(), failure.metadata().modelId(), null,
                        failure.metadata().latency(), null, AiCompletionStatus.CANCELLED));
            }
            // The sleep itself may overrun; never start an attempt that cannot fit in the remaining deadline.
            if (unclampedRemaining(budget, startNanos).compareTo(retry.minAttemptBudget()) < 0) {
                throw failure;
            }
            attempt++;
        }
    }

    private boolean canRetry(AiProviderException failure, int attempt, Duration budget, long startNanos,
            Duration backoff) {
        if (attempt >= retry.maxAttempts() || !failure.category().retryable()
                || Thread.currentThread().isInterrupted()) {
            return false;
        }
        Duration afterBackoff = remaining(budget, startNanos).minus(backoff);
        return afterBackoff.compareTo(retry.minAttemptBudget()) >= 0;
    }

    /**
     * Throttling honors the provider's Retry-After hint when present (a hint that does not fit the remaining
     * budget therefore means no retry) and otherwise waits the full max-backoff rather than hammering a provider
     * that is already rate limiting. Other transient failures use exponential backoff capped at max-backoff.
     * Every delay gets equal jitter (50%-100%), except an explicit provider hint, which is used as given.
     */
    private Duration backoff(AiProviderException failure, int attempt) {
        if (failure.category() == AiFailureCategory.THROTTLED) {
            if (failure.retryAfter() != null) {
                return failure.retryAfter();
            }
            return jittered(retry.maxBackoff().toNanos());
        }
        // initial-backoff is validated <= HARD_MAX_BACKOFF, so this shift (<= 2^20) cannot overflow a long.
        long base = retry.initialBackoff().toNanos() << Math.min(attempt - 1, 20);
        return jittered(Math.min(retry.maxBackoff().toNanos(), base));
    }

    private Duration jittered(long nanos) {
        double factor = 0.5 + 0.5 * Math.min(1.0, Math.max(0.0, jitter.getAsDouble()));
        return Duration.ofNanos((long) (nanos * factor));
    }

    private Duration remaining(Duration budget, long startNanos) {
        Duration left = unclampedRemaining(budget, startNanos);
        return left.isNegative() || left.isZero() ? Duration.ofMillis(1) : left;
    }

    private Duration unclampedRemaining(Duration budget, long startNanos) {
        return budget.minus(Duration.ofNanos(Math.max(0, nanoClock.getAsLong() - startNanos)));
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }
}
