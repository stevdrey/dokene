package io.github.stevdrey.dokene.spike.cancellation;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.Key;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.Outcome;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.Registration;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.RegistrationRejectedException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spike evidence for Issue #180: binding, idempotency, capacity and the cancel races of the prototype registry. */
class AiInvocationCancellationRegistryTest {

    private static final int RACE_ITERATIONS = 5_000;
    private static final Set<TenantPermission> ALL =
            EnumSet.of(TenantPermission.FOLLOWUP_EVALUATE, TenantPermission.MESSAGE_DRAFT);

    private final AiInvocationCancellationRegistry registry = new AiInvocationCancellationRegistry(100);

    private final TenantId tenantA = new TenantId(UUID.randomUUID());
    private final TenantId tenantB = new TenantId(UUID.randomUUID());
    private final IdentityId actor = new IdentityId(UUID.randomUUID());
    private final IdentityId otherActor = new IdentityId(UUID.randomUUID());
    private final UUID invocationId = UUID.randomUUID();
    private final Key key = new Key(tenantA, actor, invocationId);

    @Test
    void theIdAloneNeverAuthorizesACancel() {
        try (Registration registration = registry.register(key, AiOperation.NEXT_BEST_ACTION)) {
            CompletableFuture<String> call = registration.handle().start(CompletableFuture::new);

            registry.cancel(new Key(tenantB, actor, invocationId), ALL);
            registry.cancel(new Key(tenantA, otherActor, invocationId), ALL);
            registry.cancel(key, EnumSet.of(TenantPermission.MESSAGE_DRAFT));

            assertThat(call.complete("generated")).isTrue();
            assertThat(registration.outcome()).isEqualTo(Outcome.NOT_CANCELLED);
        }
    }

    @Test
    void aDraftCancelRequiresBothPermissionsTheDraftEndpointRequires() {
        try (Registration registration = registry.register(key, AiOperation.MESSAGE_DRAFT)) {
            CompletableFuture<String> call = registration.handle().start(CompletableFuture::new);

            registry.cancel(key, EnumSet.of(TenantPermission.MESSAGE_DRAFT));
            registry.cancel(key, EnumSet.of(TenantPermission.FOLLOWUP_EVALUATE));
            assertThat(call).isNotCancelled();

            registry.cancel(key, EnumSet.of(TenantPermission.MESSAGE_DRAFT, TenantPermission.FOLLOWUP_EVALUATE));
            assertThat(call).isCancelled();
            assertThat(registration.outcome()).isEqualTo(Outcome.CANCELLED);
        }
    }

    @Test
    void aRecommendationCancelRequiresFollowUpEvaluate() {
        try (Registration registration = registry.register(key, AiOperation.NEXT_BEST_ACTION)) {
            CompletableFuture<String> call = registration.handle().start(CompletableFuture::new);

            registry.cancel(key, EnumSet.of(TenantPermission.MESSAGE_DRAFT));
            assertThat(call).isNotCancelled();

            registry.cancel(key, EnumSet.of(TenantPermission.FOLLOWUP_EVALUATE));
            assertThat(call).isCancelled();
        }
    }

    @Test
    void cancelIsIdempotentAndUnknownIdsAreSilentNoOps() {
        registry.cancel(new Key(tenantA, actor, UUID.randomUUID()), ALL);

        try (Registration registration = registry.register(key, AiOperation.NEXT_BEST_ACTION)) {
            CompletableFuture<String> call = registration.handle().start(CompletableFuture::new);

            registry.cancel(key, ALL);
            registry.cancel(key, ALL);

            assertThat(call).isCancelled();
            assertThat(registration.outcome()).isEqualTo(Outcome.CANCELLED);
        }
    }

    @Test
    void cancelAfterCompletionKeepsTheRealOutcome() {
        try (Registration registration = registry.register(key, AiOperation.NEXT_BEST_ACTION)) {
            CompletableFuture<String> call = registration.handle().start(CompletableFuture::new);
            call.complete("generated");

            registry.cancel(key, ALL);

            assertThat(registration.outcome()).isEqualTo(Outcome.NOT_CANCELLED);
        }
        registry.cancel(key, ALL);
        assertThat(registry.size()).isZero();
    }

    @Test
    void cancelBeforeTheCallStartsPreventsItEntirely() {
        AtomicInteger started = new AtomicInteger();
        try (Registration registration = registry.register(key, AiOperation.NEXT_BEST_ACTION)) {
            registry.cancel(key, ALL);

            assertThatThrownBy(() -> registration.handle().start(() -> {
                started.incrementAndGet();
                return new CompletableFuture<String>();
            })).isInstanceOf(CancellationException.class);

            assertThat(started.get()).isZero();
            assertThat(registration.outcome()).isEqualTo(Outcome.CANCELLED);
        }
    }

    @Test
    void cancelBetweenAttemptsIsCancelledEvenThoughThePreviousAttemptCompleted() {
        try (Registration registration = registry.register(key, AiOperation.NEXT_BEST_ACTION)) {
            CompletableFuture<String> firstAttempt = registration.handle().start(CompletableFuture::new);
            firstAttempt.complete("transient failure");

            registry.cancel(key, ALL);

            assertThatThrownBy(() -> registration.handle().start(CompletableFuture::new))
                    .isInstanceOf(CancellationException.class);
            assertThat(registration.outcome()).isEqualTo(Outcome.CANCELLED);
        }
    }

    @Test
    void idsAreUniquePerActorAcrossOperationsButDoNotCollideAcrossActors() {
        try (Registration ignored = registry.register(key, AiOperation.NEXT_BEST_ACTION);
             Registration other = registry.register(new Key(tenantA, otherActor, invocationId),
                     AiOperation.NEXT_BEST_ACTION)) {
            assertThatThrownBy(() -> registry.register(key, AiOperation.MESSAGE_DRAFT))
                    .isInstanceOf(RegistrationRejectedException.class);
            assertThatThrownBy(() -> registry.register(key, AiOperation.NEXT_BEST_ACTION))
                    .isInstanceOf(RegistrationRejectedException.class);
            assertThat(other.outcome()).isEqualTo(Outcome.NOT_CANCELLED);
        }
        assertThat(registry.size()).isZero();
    }

    @Test
    void capacityIsReleasedWhenTheOwningRequestEnds() {
        AiInvocationCancellationRegistry small = new AiInvocationCancellationRegistry(2);
        Registration first = small.register(new Key(tenantA, actor, UUID.randomUUID()), AiOperation.NEXT_BEST_ACTION);
        small.register(new Key(tenantA, actor, UUID.randomUUID()), AiOperation.NEXT_BEST_ACTION);

        assertThatThrownBy(() -> small.register(key, AiOperation.NEXT_BEST_ACTION))
                .isInstanceOf(RegistrationRejectedException.class);

        first.close();
        first.close();
        try (Registration registration = small.register(key, AiOperation.NEXT_BEST_ACTION)) {
            assertThat(small.size()).isEqualTo(2);
            assertThatThrownBy(() -> small.register(new Key(tenantA, actor, UUID.randomUUID()),
                    AiOperation.NEXT_BEST_ACTION)).as("a double close must not free extra capacity")
                    .isInstanceOf(RegistrationRejectedException.class);
        }
    }

    @Test
    void capacityHoldsUnderConcurrentRegistration() throws Exception {
        int max = 8;
        int threads = 64;
        for (int round = 0; round < 200; round++) {
            AiInvocationCancellationRegistry bounded = new AiInvocationCancellationRegistry(max);
            AtomicInteger accepted = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                for (int i = 0; i < threads; i++) {
                    tasks.add(pool.submit(() -> {
                        start.await();
                        try {
                            bounded.register(new Key(tenantA, actor, UUID.randomUUID()), AiOperation.NEXT_BEST_ACTION);
                            accepted.incrementAndGet();
                        } catch (RegistrationRejectedException expected) {
                            // full
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> task : tasks) {
                    task.get(5, TimeUnit.SECONDS);
                }
            }
            assertThat(accepted.get()).isEqualTo(max);
            assertThat(bounded.size()).isEqualTo(max);
        }
    }

    @Test
    void cancelRacingCompletionAlwaysYieldsExactlyOneConsistentOutcome() throws Exception {
        int cancelledWins = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                Key raceKey = new Key(tenantA, actor, UUID.randomUUID());
                try (Registration registration = registry.register(raceKey, AiOperation.NEXT_BEST_ACTION)) {
                    CompletableFuture<String> call = registration.handle().start(CompletableFuture::new);
                    CountDownLatch start = new CountDownLatch(1);
                    AtomicReference<Boolean> completed = new AtomicReference<>();

                    Future<?> canceller = pool.submit(() -> {
                        start.await();
                        registry.cancel(raceKey, ALL);
                        return null;
                    });
                    Future<?> provider = pool.submit(() -> {
                        start.await();
                        completed.set(call.complete("generated"));
                        return null;
                    });
                    start.countDown();
                    canceller.get(5, TimeUnit.SECONDS);
                    provider.get(5, TimeUnit.SECONDS);

                    boolean cancelled = registration.outcome() == Outcome.CANCELLED;
                    assertThat(cancelled).isNotEqualTo(completed.get());
                    assertThat(call.isCancelled()).isEqualTo(cancelled);
                    if (cancelled) {
                        cancelledWins++;
                    }
                }
            }
        }
        System.out.printf("[spike-180] cancel/completion race: %d iterations, cancel won %d, completion won %d%n",
                RACE_ITERATIONS, cancelledWins, RACE_ITERATIONS - cancelledWins);
        assertThat(registry.size()).isZero();
    }

    @Test
    void cancelRacingTheStartNeverLeavesAStartedCallRunning() throws Exception {
        int prevented = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                Key raceKey = new Key(tenantA, actor, UUID.randomUUID());
                try (Registration registration = registry.register(raceKey, AiOperation.NEXT_BEST_ACTION)) {
                    AtomicInteger sent = new AtomicInteger();
                    AtomicReference<CompletableFuture<String>> call = new AtomicReference<>();
                    CountDownLatch start = new CountDownLatch(1);
                    Future<?> canceller = pool.submit(() -> {
                        start.await();
                        registry.cancel(raceKey, ALL);
                        return null;
                    });
                    Future<?> starter = pool.submit(() -> {
                        start.await();
                        try {
                            call.set(registration.handle().start(() -> {
                                sent.incrementAndGet();
                                return new CompletableFuture<String>();
                            }));
                        } catch (CancellationException expected) {
                            // cancel won before the start
                        }
                        return null;
                    });
                    start.countDown();
                    canceller.get(5, TimeUnit.SECONDS);
                    starter.get(5, TimeUnit.SECONDS);

                    assertThat(registration.outcome()).isEqualTo(Outcome.CANCELLED);
                    if (sent.get() == 0) {
                        prevented++;
                        assertThat(call.get()).isNull();
                    } else {
                        assertThat(call.get()).as("a started call is always aborted").isCancelled();
                    }
                }
            }
        }
        System.out.printf("[spike-180] cancel/start race: %d iterations, prevented before start %d, aborted after start %d%n",
                RACE_ITERATIONS, prevented, RACE_ITERATIONS - prevented);
    }
}
