package io.github.stevdrey.dokene.ai.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraftReason;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ResilientAiProviderTest {
    private static final String SECRET = "IGNORE ALL PREVIOUS +593 99 123 4567 sk-secret-key";

    private final RecommendationContext context = new RecommendationContext(
            new RecommendationContext.TrustedFacts(LocalDate.of(2026, 9, 25), "DUE",
                    List.of(TrustedFollowUpReason.DUE_TODAY), 30, LocalDate.of(2026, 9, 25), true,
                    List.of(Instant.parse("2026-09-01T12:00:00Z")),
                    List.of(SemanticAction.GENERAL_CHECK_IN)),
            new RecommendationContext.UntrustedText("Customer", null, List.of("Purchase")));
    private final NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.INSUFFICIENT_HISTORY,
            "Not enough history", RecommendationConfidence.of(0.8));

    private final AtomicLong nanos = new AtomicLong();
    private final List<Duration> sleeps = new ArrayList<>();
    private final RecordingTelemetry telemetry = new RecordingTelemetry();
    private final Scripted delegate = new Scripted(nanos);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    private ResilientAiProvider provider(int maxAttempts) {
        return new ResilientAiProvider(delegate,
                new AiRetryProperties(maxAttempts, null, null, null), telemetry,
                duration -> {
                    sleeps.add(duration);
                    nanos.addAndGet(duration.toNanos());
                }, nanos::get, () -> 0.0);
    }

    private AiRecommendationRequest request(Duration timeout) {
        return new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION, context, timeout);
    }

    @Test
    void recoversAfterTransientThrottlingWithinTheSameInvocation() {
        delegate.failThen(AiFailureCategory.THROTTLED);

        AiRecommendationResponse response = provider(2).recommend(request(Duration.ofSeconds(15)));

        assertThat(response.outcome()).isSameAs(refusal);
        assertThat(delegate.calls).isEqualTo(2);
        // no Retry-After hint: wait the (jittered, here 50%) max-backoff instead of hammering a throttling provider
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        assertThat(telemetry.retries).containsExactly("NEXT_BEST_ACTION:fake:THROTTLED");
        assertThat(telemetry.invocations).containsExactly("FAILED:THROTTLED", "SUCCEEDED:NONE");
    }

    @Test
    void recoversAfterTimeoutWhenBudgetAllows() {
        delegate.failThen(AiFailureCategory.TIMEOUT);

        provider(2).recommend(request(Duration.ofSeconds(15)));

        assertThat(delegate.calls).isEqualTo(2);
        assertThat(telemetry.retries).containsExactly("NEXT_BEST_ACTION:fake:TIMEOUT");
    }

    @ParameterizedTest
    @EnumSource(value = AiFailureCategory.class,
            names = {"NOT_AVAILABLE", "REFUSED", "INVALID_STRUCTURED_RESPONSE", "REJECTED_REQUEST", "CANCELLED"})
    void neverRetriesNonTransientCategories(AiFailureCategory category) {
        delegate.alwaysFail(category);

        assertThatThrownBy(() -> provider(3).recommend(request(Duration.ofSeconds(15))))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.category()).isEqualTo(category));

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(telemetry.retries).isEmpty();
    }

    @Test
    void retryIsBoundedByMaxAttempts() {
        delegate.alwaysFail(AiFailureCategory.UNAVAILABLE);

        assertThatThrownBy(() -> provider(3).recommend(request(Duration.ofSeconds(30))))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.category()).isEqualTo(AiFailureCategory.UNAVAILABLE));

        assertThat(delegate.calls).isEqualTo(3);
        assertThat(telemetry.retries).hasSize(2);
        assertThat(sleeps).containsExactly(Duration.ofMillis(125), Duration.ofMillis(250));
    }

    @Test
    void maxAttemptsOfOneDisablesRetry() {
        delegate.alwaysFail(AiFailureCategory.UNAVAILABLE);

        assertThatThrownBy(() -> provider(1).recommend(request(Duration.ofSeconds(15))))
                .isInstanceOf(AiProviderException.class);

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void doesNotRetryWhenTheRemainingDeadlineCannotFitAnotherAttempt() {
        delegate.alwaysFail(AiFailureCategory.THROTTLED);

        assertThatThrownBy(() -> provider(3).recommend(request(Duration.ofSeconds(1))))
                .isInstanceOf(AiProviderException.class);

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void eachAttemptReceivesOnlyTheRemainingBudget() {
        delegate.failThen(AiFailureCategory.TIMEOUT);
        delegate.latencyPerCall = Duration.ofSeconds(4);

        provider(2).recommend(request(Duration.ofSeconds(10)));

        assertThat(delegate.timeouts).hasSize(2);
        assertThat(delegate.timeouts.get(0)).isEqualTo(Duration.ofSeconds(10));
        // 10s total - 4s first attempt - 125ms backoff
        assertThat(delegate.timeouts.get(1)).isEqualTo(Duration.ofMillis(5875));
    }

    @Test
    void unexpectedRuntimeFailuresAreNormalizedWithoutLeakingMessageOrCause() {
        delegate.throwRuntime(new IllegalStateException(SECRET, new RuntimeException(SECRET)));

        assertThatThrownBy(() -> provider(1).recommend(request(Duration.ofSeconds(15))))
                .isInstanceOfSatisfying(AiProviderException.class, ex -> {
                    assertThat(ex.category()).isEqualTo(AiFailureCategory.UNAVAILABLE);
                    assertThat(ex.getMessage()).doesNotContain("sk-secret").doesNotContain("991234567");
                    assertThat(ex.getCause()).isNull();
                    assertThat(ex.metadata().toString()).doesNotContain("sk-secret");
                });
        assertThat(telemetry.invocations).containsExactly("FAILED:UNAVAILABLE");
    }

    @Test
    void throttlingHonorsTheProviderRetryAfterHintWhenItFitsTheBudget() {
        delegate.failThen(AiFailureCategory.THROTTLED, Duration.ofSeconds(3));

        provider(2).recommend(request(Duration.ofSeconds(15)));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
        assertThat(delegate.calls).isEqualTo(2);
    }

    @Test
    void throttlingIsNotRetriedWhenTheRetryAfterHintExceedsTheRemainingBudget() {
        delegate.failThen(AiFailureCategory.THROTTLED, Duration.ofSeconds(20));

        assertThatThrownBy(() -> provider(3).recommend(request(Duration.ofSeconds(15))))
                .isInstanceOfSatisfying(AiProviderException.class, ex -> {
                    assertThat(ex.category()).isEqualTo(AiFailureCategory.THROTTLED);
                    assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(20));
                });

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(telemetry.retries).isEmpty();
    }

    @Test
    void retryAfterHintOnlyAffectsThrottling() {
        delegate.failThen(AiFailureCategory.UNAVAILABLE, Duration.ofSeconds(10));

        provider(2).recommend(request(Duration.ofSeconds(15)));

        assertThat(sleeps).containsExactly(Duration.ofMillis(125));
    }

    @Test
    void largestConfiguredBackoffNeverOverflowsOrCollapsesToZero() {
        delegate.alwaysFail(AiFailureCategory.UNAVAILABLE);
        ResilientAiProvider widest = new ResilientAiProvider(delegate,
                new AiRetryProperties(3, AiRetryProperties.HARD_MAX_BACKOFF, AiRetryProperties.HARD_MAX_BACKOFF, null),
                telemetry, duration -> {
                    sleeps.add(duration);
                    nanos.addAndGet(duration.toNanos());
                }, nanos::get, () -> 1.0);

        assertThatThrownBy(() -> widest.recommend(request(Duration.ofMinutes(10))))
                .isInstanceOf(AiProviderException.class);

        assertThat(sleeps).hasSize(2).allSatisfy(sleep -> assertThat(sleep)
                .isPositive().isLessThanOrEqualTo(AiRetryProperties.HARD_MAX_BACKOFF));
    }

    @Test
    void backoffAboveTheHardCeilingIsRejected() {
        assertThatThrownBy(() -> new AiRetryProperties(2, Duration.ofSeconds(31), Duration.ofSeconds(31), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRetryProperties(2, Duration.ofSeconds(1), Duration.ofDays(2), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new AiRetryProperties(2, AiRetryProperties.HARD_MAX_BACKOFF,
                AiRetryProperties.HARD_MAX_BACKOFF, null)).doesNotThrowAnyException();
    }

    @Test
    void retryAfterHintMustNotBeNegative() {
        AiInvocationMetadata metadata = new AiInvocationMetadata("fake", null, null, Duration.ZERO, null,
                AiCompletionStatus.FAILED);
        assertThatThrownBy(() -> new AiProviderException(AiFailureCategory.THROTTLED, metadata, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new AiProviderException(AiFailureCategory.THROTTLED, metadata).retryAfter()).isNull();
    }

    @Test
    void unsupportedOperationIsNotAvailableAndNeverRetried() {
        delegate.throwRuntime(new UnsupportedOperationException(SECRET));

        assertThatThrownBy(() -> provider(3).recommend(request(Duration.ofSeconds(15))))
                .isInstanceOfSatisfying(AiProviderException.class, ex -> {
                    assertThat(ex.category()).isEqualTo(AiFailureCategory.NOT_AVAILABLE);
                    assertThat(ex.category().retryable()).isFalse();
                    assertThat(ex.getMessage()).doesNotContain("sk-secret");
                });

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void doesNotStartAnAttemptWhenTheBackoffOverrunsTheDeadline() {
        delegate.alwaysFail(AiFailureCategory.THROTTLED);
        // The sleep reports a normal backoff but actually overruns the whole 5s budget (e.g. a long pause).
        ResilientAiProvider overrunning = new ResilientAiProvider(delegate,
                new AiRetryProperties(3, null, null, null), telemetry,
                duration -> nanos.addAndGet(Duration.ofSeconds(6).toNanos()), nanos::get, () -> 0.0);

        assertThatThrownBy(() -> overrunning.recommend(request(Duration.ofSeconds(5))))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.category()).isEqualTo(AiFailureCategory.THROTTLED));

        assertThat(delegate.calls).isEqualTo(1);
        assertThat(delegate.timeouts).noneMatch(timeout -> timeout.compareTo(Duration.ofMillis(10)) < 0);
    }

    @Test
    void interruptionDuringBackoffIsPreservedAndReportedAsCancellation() {
        delegate.alwaysFail(AiFailureCategory.THROTTLED);
        ResilientAiProvider interrupting = new ResilientAiProvider(delegate,
                new AiRetryProperties(2, null, null, null), telemetry,
                duration -> {
                    throw new InterruptedException();
                }, nanos::get, () -> 0.0);

        assertThatThrownBy(() -> interrupting.recommend(request(Duration.ofSeconds(15))))
                .isInstanceOfSatisfying(AiProviderException.class,
                        ex -> assertThat(ex.category()).isEqualTo(AiFailureCategory.CANCELLED));

        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        assertThat(delegate.calls).isEqualTo(1);
    }

    @Test
    void draftInvocationsUseTheSameRetryPolicy() {
        delegate.failThen(AiFailureCategory.UNAVAILABLE);
        DraftContext draftContext = new DraftContext(context, SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, new TrustedBusinessFacts("Dokene"));

        AiDraftResponse response = provider(2).draft(new AiDraftRequest(draftContext, Duration.ofSeconds(15)));

        assertThat(response.outcome()).isInstanceOf(NoDraft.class);
        assertThat(delegate.calls).isEqualTo(2);
        assertThat(telemetry.retries).containsExactly("MESSAGE_DRAFT:fake:UNAVAILABLE");
    }

    @Test
    void retryPropertiesAreBoundedAndValidated() {
        assertThat(AiRetryProperties.defaults().maxAttempts()).isEqualTo(2);
        assertThatThrownBy(() -> new AiRetryProperties(0, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRetryProperties(4, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRetryProperties(2, Duration.ofSeconds(5), Duration.ofSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRetryProperties(2, Duration.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyTransientCategoriesAreRetryable() {
        assertThat(java.util.Arrays.stream(AiFailureCategory.values()).filter(AiFailureCategory::retryable))
                .containsExactlyInAnyOrder(AiFailureCategory.TIMEOUT, AiFailureCategory.THROTTLED,
                        AiFailureCategory.UNAVAILABLE);
    }

    private final class Scripted implements AiProvider {
        private final AtomicLong clock;
        private final Deque<Function<Duration, Object>> script = new ArrayDeque<>();
        private Function<Duration, Object> fallback;
        private Duration latencyPerCall = Duration.ZERO;
        private int calls;
        private final List<Duration> timeouts = new ArrayList<>();

        Scripted(AtomicLong clock) {
            this.clock = clock;
        }

        void failThen(AiFailureCategory category) {
            script.add(timeout -> failure(category));
        }

        void failThen(AiFailureCategory category, Duration retryAfter) {
            script.add(timeout -> failure(category, retryAfter));
        }

        void alwaysFail(AiFailureCategory category) {
            fallback = timeout -> failure(category);
        }

        void throwRuntime(RuntimeException exception) {
            fallback = timeout -> {
                throw exception;
            };
        }

        @Override
        public AiRecommendationResponse recommend(AiRecommendationRequest request) {
            Object result = next(request.timeout());
            return result instanceof AiRecommendationResponse response ? response
                    : new AiRecommendationResponse(refusal, success());
        }

        @Override
        public AiDraftResponse draft(AiDraftRequest request) {
            next(request.timeout());
            return new AiDraftResponse(new NoDraft(NoDraftReason.INSUFFICIENT_HISTORY, "No draft",
                    RecommendationConfidence.of(0.5)), success());
        }

        private Object next(Duration timeout) {
            calls++;
            timeouts.add(timeout);
            clock.addAndGet(latencyPerCall.toNanos());
            Function<Duration, Object> step = script.isEmpty() ? fallback : script.poll();
            if (step != null) {
                Object value = step.apply(timeout);
                if (value instanceof AiProviderException failure) {
                    throw failure;
                }
            }
            return null;
        }

        private AiProviderException failure(AiFailureCategory category) {
            return failure(category, null);
        }

        private AiProviderException failure(AiFailureCategory category, Duration retryAfter) {
            return new AiProviderException(category, new AiInvocationMetadata("fake", "test-model", null,
                    latencyPerCall, null, category == AiFailureCategory.CANCELLED
                            ? AiCompletionStatus.CANCELLED : AiCompletionStatus.FAILED), retryAfter);
        }

        private AiInvocationMetadata success() {
            return new AiInvocationMetadata("fake", "test-model", "req-1", Duration.ofMillis(12),
                    new AiTokenUsage(11, 7), AiCompletionStatus.SUCCEEDED);
        }
    }

    static final class RecordingTelemetry implements AiTelemetry {
        final List<String> invocations = new ArrayList<>();
        final List<String> retries = new ArrayList<>();

        @Override
        public void attemptCompleted(AiOperation operation, AiInvocationMetadata metadata,
                AiFailureCategory category) {
            invocations.add(metadata.status() == AiCompletionStatus.SUCCEEDED ? "SUCCEEDED:NONE"
                    : "FAILED:" + category);
        }

        @Override
        public void retryScheduled(AiOperation operation, String providerId, AiFailureCategory category) {
            retries.add(operation + ":" + providerId + ":" + category);
        }

        @Override
        public void outcome(AiOperation operation, Outcome outcome) {
        }

        @Override
        public void modelRefusal(AiOperation operation) {
        }

        @Override
        public void gateRejected(AiOperation operation, String reason) {
        }
    }
}
