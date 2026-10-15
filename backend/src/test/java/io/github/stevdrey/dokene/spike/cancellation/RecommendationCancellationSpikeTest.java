package io.github.stevdrey.dokene.spike.cancellation;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiCorrelationSource;
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
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.application.RetryGuard;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.ActionGateDecision;
import io.github.stevdrey.dokene.followup.application.ActionGateRejectionReason;
import io.github.stevdrey.dokene.followup.application.AiActionGate;
import io.github.stevdrey.dokene.followup.application.AiOutcomeAuditPort;
import io.github.stevdrey.dokene.followup.application.AiOutcomeReporter;
import io.github.stevdrey.dokene.followup.application.AiUnavailableReason;
import io.github.stevdrey.dokene.followup.application.DefaultFollowUpRecommendationRateLimiter;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationResult;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.PurchaseBaseline;
import io.github.stevdrey.dokene.followup.application.RecommendationContextAssembler;
import io.github.stevdrey.dokene.followup.application.RecommendationRateLimitExceededException;
import io.github.stevdrey.dokene.followup.application.RecommendationStatus;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.Key;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.Registration;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spike evidence for Issue #180 through the real recommendation service, retry decorator, outcome reporter and
 * rate limiter: a won cancel is audited once as {@code FAILED/CANCELLED} without retry, a lost cancel keeps
 * {@code GENERATED}, and permits consumed by cancelled calls stay consumed.
 */
class RecommendationCancellationSpikeTest {

    private static final int ACTOR_PERMITS = 3;
    private static final Set<TenantPermission> ALL =
            EnumSet.of(TenantPermission.FOLLOWUP_EVALUATE, TenantPermission.MESSAGE_DRAFT);

    private final TenantId tenantId = TenantId.random();
    private final IdentityId actorId = new IdentityId(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final LocalDate tenantDate = LocalDate.of(2026, 10, 14);
    private final Instant lastPurchase = Instant.parse("2026-09-01T12:00:00Z");
    private final FollowUpEvaluation due = new FollowUpEvaluation(customerId, FollowUpStatus.DUE,
            List.of(FollowUpReason.DUE_TODAY), Instant.now(), tenantDate, ZoneId.of("UTC"), tenantDate,
            FollowUpTimingSource.LAST_PURCHASE, 30, lastPurchase);
    private final RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
            due, context(), List.of(new PurchaseBaseline(new PurchaseId(UUID.randomUUID()), 0L, lastPurchase)), 1L);
    private final ActionRecommendation action = new ActionRecommendation(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
            SemanticTemplateIntent.REPEAT_PURCHASE, "Purchase cadence reached", RecommendationConfidence.of(0.8),
            DraftVariables.empty());

    private final RecommendationContextAssembler assembler = mock();
    private final AiActionGate gate = mock();
    private final TenantAuthorizationService authorization = mock();
    private final ScopedValueTenantContextProvider contexts = new ScopedValueTenantContextProvider();
    private final FollowUpService followUps = mock();

    private final AiInvocationCancellationRegistry registry =
            new AiInvocationCancellationRegistry(100);
    private final CancellableProvider provider = new CancellableProvider();
    private final RecordingAudit audit = new RecordingAudit();
    private final DefaultFollowUpRecommendationRateLimiter rateLimiter =
            new DefaultFollowUpRecommendationRateLimiter(100, ACTOR_PERMITS, Duration.ofSeconds(5), Clock.systemUTC());
    private final TenantContext requestContext =
            new TenantContext(tenantId, actorId, TenantMembershipId.random(), TenantRole.OPERATOR);
    private final AtomicReference<AiInvocationCancellationRegistry.Outcome> lastOutcome = new AtomicReference<>();
    private FollowUpRecommendationService recommendations;

    @BeforeEach
    void setUp() {
        when(assembler.assemble(customerId)).thenReturn(assembly);
        when(followUps.customerPolicy(customerId))
                .thenReturn(new CustomerFollowUpPolicy(tenantId, customerId, 30, null, null, null, 1L));
        when(followUps.evaluateSnapshot(customerId))
                .thenReturn(new FollowUpService.FollowUpEvaluationSnapshot(due, 1L));
        when(gate.evaluate(eq(customerId), eq(assembly), any()))
                .thenReturn(ActionGateDecision.accepted(action, due, 1L));
        AiResilience resilience = new AiResilience(
                new AiRetryProperties(2, Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(10)),
                AiTelemetry.noop());
        AiOutcomeReporter reporter = new AiOutcomeReporter(AiTelemetry.noop(), audit, AiCorrelationSource.none());
        recommendations = new FollowUpRecommendationService(provider, assembler, gate, authorization, contexts,
                followUps, rateLimiter, resilience, reporter);
    }

    @Test
    void aWonCancelIsAuditedOnceAsCancelledInTheRequestContextAndIsNotRetried() throws Exception {
        Key key = key();
        Invocation invocation = start(key);
        assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();

        registry.cancel(key, ALL);
        FollowUpRecommendationResult result = invocation.await();

        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.CANCELLED);
        assertThat(audit.events).containsExactly("failed:NEXT_BEST_ACTION:CANCELLED");
        assertThat(audit.tenants).containsExactly(tenantId);
        assertThat(provider.invocations.get()).as("no retry after a cancel").isEqualTo(1);
        assertThat(provider.providerCalls.get()).isEqualTo(1);
        assertThat(invocation.outcome.get()).isEqualTo(AiInvocationCancellationRegistry.Outcome.CANCELLED);
    }

    @Test
    void aCancelDuringRetryBackoffPreventsTheRetryAndIsAuditedAsCancelled() throws Exception {
        Key key = key();
        // The first attempt completes with a retryable failure; the cancel lands before the retry starts.
        provider.failFirstAttemptThen(() -> registry.cancel(key, ALL));

        FollowUpRecommendationResult result = runRegistered(key);

        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.CANCELLED);
        assertThat(provider.invocations.get()).as("the retry was attempted").isEqualTo(2);
        assertThat(provider.providerCalls.get()).as("but never reached the provider").isEqualTo(1);
        assertThat(audit.events).containsExactly("failed:NEXT_BEST_ACTION:CANCELLED");
        assertThat(lastOutcome.get()).isEqualTo(AiInvocationCancellationRegistry.Outcome.CANCELLED);
    }

    @Test
    void theVirtualThreadWorkerMechanismIsAuditedAsCancelledAndLeavesTheRequestThreadClean() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AiProvider blockingAdapter = new AiProvider() {
            @Override
            public AiRecommendationResponse recommend(AiRecommendationRequest request) {
                calls.incrementAndGet();
                blocked.countDown();
                try {
                    // Stands in for a socket read: interrupting the virtual worker ends the wait.
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(
                            "test", "test-model", null, Duration.ZERO, null, AiCompletionStatus.CANCELLED));
                }
                return new AiRecommendationResponse(action, success());
            }
        };
        recommendations = new FollowUpRecommendationService(new VirtualThreadCancellableProvider(blockingAdapter),
                assembler, gate, authorization, contexts, followUps, rateLimiter,
                new AiResilience(new AiRetryProperties(2, Duration.ofMillis(1), Duration.ofMillis(2),
                        Duration.ofMillis(10)), AiTelemetry.noop()),
                new AiOutcomeReporter(AiTelemetry.noop(), audit, AiCorrelationSource.none()));
        Key key = key();
        CompletableFuture<FollowUpRecommendationResult> result = new CompletableFuture<>();
        AtomicReference<Boolean> requestThreadInterrupted = new AtomicReference<>();
        Thread.ofPlatform().start(() -> {
            try {
                FollowUpRecommendationResult value = runRegistered(key);
                requestThreadInterrupted.set(Thread.currentThread().isInterrupted());
                result.complete(value);
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });

        assertThat(blocked.await(5, TimeUnit.SECONDS)).isTrue();
        registry.cancel(key, ALL);

        FollowUpRecommendationResult value = result.get(5, TimeUnit.SECONDS);
        assertThat(value.unavailableReason()).isEqualTo(AiUnavailableReason.CANCELLED);
        assertThat(calls.get()).as("no retry").isEqualTo(1);
        assertThat(audit.events).containsExactly("failed:NEXT_BEST_ACTION:CANCELLED");
        assertThat(lastOutcome.get()).isEqualTo(AiInvocationCancellationRegistry.Outcome.CANCELLED);
        assertThat(requestThreadInterrupted.get()).as("the request thread is never interrupted").isFalse();
    }

    @Test
    void aCancelDuringBackoffIsStoppedByTheRetryGuardWithoutCountingARetryOrAnAttempt() {
        AiCancellationHandle handle = new AiCancellationHandle();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();
        AiTelemetry telemetry = new AiTelemetry() {
            @Override
            public void attemptCompleted(AiOperation operation, AiInvocationMetadata metadata,
                    AiFailureCategory category) {
                attempts.incrementAndGet();
            }

            @Override
            public void outcome(AiOperation operation, AiInvocationMetadata metadata, Outcome outcome) {
            }

            @Override
            public void retryScheduled(AiOperation operation, String providerId, AiFailureCategory category) {
                retries.incrementAndGet();
            }

            @Override
            public void modelRefusal(AiOperation operation) {
            }

            @Override
            public void gateRejected(AiOperation operation, String reason) {
            }
        };
        AiProvider worker = new VirtualThreadCancellableProvider(request -> {
            providerCalls.incrementAndGet();
            throw new AiProviderException(AiFailureCategory.UNAVAILABLE, new AiInvocationMetadata(
                    "fake", "fake-model", "req-1", Duration.ofMillis(3), null, AiCompletionStatus.FAILED));
        });
        // The cancel lands after the failed attempt finished, i.e. during the retry backoff.
        AiProvider cancelAfterAttempt = request -> {
            try {
                return worker.recommend(request);
            } finally {
                handle.cancel();
            }
        };
        // Production would add this check to the RetryGuard FollowUpRecommendationService already builds.
        RetryGuard cancellationGuard = previous -> {
            if (handle.isCancelled()) {
                throw new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(
                        previous.providerId(), previous.modelId(), previous.providerRequestId(), previous.latency(),
                        previous.usage(), AiCompletionStatus.CANCELLED));
            }
        };
        AiProvider resilient = new AiResilience(new AiRetryProperties(2, Duration.ofMillis(1), Duration.ofMillis(2),
                Duration.ofMillis(10)), telemetry).wrap(cancelAfterAttempt, cancellationGuard);

        assertThatThrownBy(() -> ScopedValue.where(CancellableHttpClient.HANDLE, handle)
                .call(() -> resilient.recommend(new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION, context(),
                        Duration.ofSeconds(3)))))
                .isInstanceOfSatisfying(AiProviderException.class, e -> {
                    assertThat(e.category()).isEqualTo(AiFailureCategory.CANCELLED);
                    assertThat(e.metadata().providerId()).isEqualTo("fake");
                    assertThat(e.metadata().modelId()).isEqualTo("fake-model");
                });
        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(attempts.get()).as("only the attempt that really ran is recorded").isEqualTo(1);
        assertThat(retries.get()).as("no retry is counted").isZero();
    }

    @Test
    void aCancelThatLosesTheRaceKeepsTheGeneratedOutcome() throws Exception {
        Key key = key();
        Invocation invocation = start(key);
        assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();

        provider.respond(new AiRecommendationResponse(action, success()));
        FollowUpRecommendationResult result = invocation.await();
        registry.cancel(key, ALL);

        assertThat(result.status()).isEqualTo(RecommendationStatus.AVAILABLE);
        assertThat(audit.events).containsExactly("generated:NEXT_BEST_ACTION");
        assertThat(invocation.outcome.get()).isEqualTo(AiInvocationCancellationRegistry.Outcome.NOT_CANCELLED);
    }

    @Test
    void permitsConsumedByCancelledCallsStayConsumed() throws Exception {
        for (int i = 0; i < ACTOR_PERMITS; i++) {
            Key key = key();
            provider.reset();
            Invocation invocation = start(key);
            assertThat(provider.started.await(5, TimeUnit.SECONDS)).isTrue();
            registry.cancel(key, ALL);
            assertThat(invocation.await().unavailableReason()).isEqualTo(AiUnavailableReason.CANCELLED);
        }

        Key next = key();
        assertThatThrownBy(() -> runRegistered(next)).isInstanceOf(RecommendationRateLimitExceededException.class);
        assertThat(audit.events).hasSize(ACTOR_PERMITS).allMatch("failed:NEXT_BEST_ACTION:CANCELLED"::equals);
    }

    @Test
    void aCancelDuringContextAssemblyPreventsTheProviderCallButStillConsumesThePermit() throws Exception {
        Key key = key();
        when(assembler.assemble(customerId)).thenAnswer(invocation -> {
            registry.cancel(key, ALL);
            return assembly;
        });

        FollowUpRecommendationResult result = runRegistered(key);

        assertThat(result.unavailableReason()).isEqualTo(AiUnavailableReason.CANCELLED);
        assertThat(provider.providerCalls.get()).as("no provider HTTP call was started").isZero();
        assertThat(audit.events).containsExactly("failed:NEXT_BEST_ACTION:CANCELLED");
        for (int i = 1; i < ACTOR_PERMITS; i++) {
            when(assembler.assemble(customerId)).thenReturn(assembly);
            provider.reset();
            provider.respond(new AiRecommendationResponse(action, success()));
            runRegistered(key());
        }
        assertThatThrownBy(() -> runRegistered(key())).isInstanceOf(RecommendationRateLimitExceededException.class);
    }

    private Key key() {
        return new Key(tenantId, actorId, UUID.randomUUID());
    }

    private FollowUpRecommendationResult runRegistered(Key key) throws Exception {
        try (Registration registration = registry.register(key, AiOperation.NEXT_BEST_ACTION)) {
            try {
                return recommendIn(registration);
            } finally {
                lastOutcome.set(registration.outcome());
            }
        }
    }

    /** Mirrors a request thread: tenant context bound by the filter, cancellation handle bound per invocation. */
    private FollowUpRecommendationResult recommendIn(Registration registration) throws Exception {
        return contexts.callWithContext(requestContext, () -> ScopedValue
                .where(CancellableHttpClient.HANDLE, registration.handle())
                .call(() -> recommendations.recommendSafe(customerId, Duration.ofSeconds(3), null)));
    }

    private Invocation start(Key key) {
        Invocation invocation = new Invocation();
        Thread.ofPlatform().start(() -> {
            try (Registration registration = registry.register(key, AiOperation.NEXT_BEST_ACTION)) {
                FollowUpRecommendationResult result = recommendIn(registration);
                invocation.outcome.set(registration.outcome());
                invocation.result.complete(result);
            } catch (Throwable t) {
                invocation.result.completeExceptionally(t);
            } finally {
                // Production must clear this before the blocking audit write; the reporter here is in-memory.
                Thread.interrupted();
            }
        });
        return invocation;
    }

    private RecommendationContext context() {
        return new RecommendationContext(
                new RecommendationContext.TrustedFacts(tenantDate, "DUE", List.of(TrustedFollowUpReason.DUE_TODAY),
                        30, tenantDate, true, List.of(lastPurchase), Arrays.asList(SemanticAction.values())),
                new RecommendationContext.UntrustedText("Ana", "Cliente habitual", List.of("Cafe")));
    }

    private static AiInvocationMetadata success() {
        return new AiInvocationMetadata("test", "test-model", "req-1", Duration.ofMillis(10), null,
                AiCompletionStatus.SUCCEEDED);
    }

    private static final class Invocation {
        final CompletableFuture<FollowUpRecommendationResult> result = new CompletableFuture<>();
        final AtomicReference<AiInvocationCancellationRegistry.Outcome> outcome = new AtomicReference<>();

        FollowUpRecommendationResult await() throws Exception {
            try {
                return result.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception cause ? cause : e;
            }
        }
    }

    /**
     * Stands in for the OpenAI adapter behind {@link CancellableHttpClient}: the "HTTP call" is a future attached to
     * the bound handle, and a cancellation maps to {@code CANCELLED} the way the adapter maps it.
     */
    private static final class CancellableProvider implements AiProvider {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicInteger providerCalls = new AtomicInteger();
        volatile CountDownLatch started = new CountDownLatch(1);
        private volatile CompletableFuture<AiRecommendationResponse> call = new CompletableFuture<>();
        private volatile Runnable afterFailedFirstAttempt;

        void reset() {
            started = new CountDownLatch(1);
            call = new CompletableFuture<>();
            afterFailedFirstAttempt = null;
        }

        void failFirstAttemptThen(Runnable afterAttempt) {
            afterFailedFirstAttempt = afterAttempt;
        }

        void respond(AiRecommendationResponse response) {
            call.complete(response);
        }

        @Override
        public AiRecommendationResponse recommend(AiRecommendationRequest request) {
            int attempt = invocations.incrementAndGet();
            AiCancellationHandle handle = CancellableHttpClient.HANDLE.get();
            Runnable afterFailure = afterFailedFirstAttempt;
            CompletableFuture<AiRecommendationResponse> attemptCall;
            try {
                attemptCall = handle.start(() -> {
                    providerCalls.incrementAndGet();
                    return afterFailure != null && attempt == 1 ? new CompletableFuture<>() : call;
                });
            } catch (CancellationException e) {
                throw cancelled();
            }
            if (afterFailure != null && attempt == 1) {
                attemptCall.complete(null);
                afterFailure.run();
                throw new AiProviderException(AiFailureCategory.UNAVAILABLE, new AiInvocationMetadata(
                        "test", "test-model", null, Duration.ZERO, null, AiCompletionStatus.FAILED));
            }
            started.countDown();
            try {
                return attemptCall.get();
            } catch (CancellationException e) {
                throw cancelled();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw cancelled();
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }

        private static AiProviderException cancelled() {
            return new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(
                    "test", "test-model", null, Duration.ZERO, null, AiCompletionStatus.CANCELLED));
        }
    }

    private final class RecordingAudit implements AiOutcomeAuditPort {
        final List<String> events = new CopyOnWriteArrayList<>();
        final List<TenantId> tenants = new CopyOnWriteArrayList<>();

        @Override
        public void generated(CustomerId customer, AiOperation operation) {
            record("generated:" + operation);
        }

        @Override
        public void modelRefused(CustomerId customer, AiOperation operation) {
            record("modelRefused:" + operation);
        }

        @Override
        public void gateRejected(CustomerId customer, AiOperation operation, ActionGateRejectionReason reason) {
            record("gateRejected:" + operation + ":" + reason);
        }

        @Override
        public void failed(CustomerId customer, AiOperation operation, AiUnavailableReason reason) {
            record("failed:" + operation + ":" + reason);
        }

        private void record(String event) {
            events.add(event);
            // The durable recorder resolves the tenant from the request context bound on the writing thread;
            // requireCurrent() fails if the cancelled invocation reported outside that context.
            tenants.add(contexts.requireCurrent().tenantId());
        }
    }
}
