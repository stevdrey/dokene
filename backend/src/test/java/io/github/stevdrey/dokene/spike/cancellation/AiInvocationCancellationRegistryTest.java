package io.github.stevdrey.dokene.spike.cancellation;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.Key;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.Outcome;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.Registration;
import io.github.stevdrey.dokene.spike.cancellation.AiInvocationCancellationRegistry.RegistrationRejectedException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spike evidence for Issue #180: binding, idempotency and the cancel/completion race of the prototype registry. */
class AiInvocationCancellationRegistryTest {

    private static final int RACE_ITERATIONS = 5_000;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T12:00:00Z"));
    private final AiInvocationCancellationRegistry registry =
            new AiInvocationCancellationRegistry(100, Duration.ofSeconds(60), clock);

    private final TenantId tenantA = new TenantId(UUID.randomUUID());
    private final TenantId tenantB = new TenantId(UUID.randomUUID());
    private final IdentityId actor = new IdentityId(UUID.randomUUID());
    private final IdentityId otherActor = new IdentityId(UUID.randomUUID());
    private final UUID invocationId = UUID.randomUUID();
    private final Key key = new Key(tenantA, actor, AiOperation.NEXT_BEST_ACTION, invocationId);

    @Test
    void theIdAloneNeverAuthorizesACancel() {
        try (Registration registration = registry.register(key)) {
            CompletableFuture<String> call = new CompletableFuture<>();
            registration.handle().attach(call);

            registry.cancel(new Key(tenantB, actor, AiOperation.NEXT_BEST_ACTION, invocationId));
            registry.cancel(new Key(tenantA, otherActor, AiOperation.NEXT_BEST_ACTION, invocationId));
            registry.cancel(new Key(tenantA, actor, AiOperation.MESSAGE_DRAFT, invocationId));

            assertThat(call.complete("generated")).isTrue();
            assertThat(registration.outcome()).isEqualTo(Outcome.NOT_CANCELLED);
        }
    }

    @Test
    void cancelIsIdempotentAndUnknownIdsAreSilentNoOps() {
        registry.cancel(new Key(tenantA, actor, AiOperation.NEXT_BEST_ACTION, UUID.randomUUID()));

        try (Registration registration = registry.register(key)) {
            CompletableFuture<String> call = new CompletableFuture<>();
            registration.handle().attach(call);

            registry.cancel(key);
            registry.cancel(key);

            assertThat(call).isCancelled();
            assertThat(registration.outcome()).isEqualTo(Outcome.CANCELLED);
        }
    }

    @Test
    void cancelAfterCompletionKeepsTheRealOutcome() {
        try (Registration registration = registry.register(key)) {
            CompletableFuture<String> call = new CompletableFuture<>();
            registration.handle().attach(call);
            call.complete("generated");

            registry.cancel(key);

            assertThat(registration.outcome()).isEqualTo(Outcome.NOT_CANCELLED);
        }
        registry.cancel(key);
        assertThat(registry.size()).isZero();
    }

    @Test
    void cancelBeforeTheCallStartsIsAppliedWhenItAttaches() {
        try (Registration registration = registry.register(key)) {
            registry.cancel(key);
            CompletableFuture<String> call = new CompletableFuture<>();

            registration.handle().attach(call);

            assertThat(call).isCancelled();
            assertThat(registration.outcome()).isEqualTo(Outcome.CANCELLED);
        }
    }

    @Test
    void duplicateIdsForTheSameActorAreRejectedButDoNotLeakAcrossActors() {
        try (Registration ignored = registry.register(key);
             Registration other = registry.register(new Key(tenantA, otherActor, AiOperation.NEXT_BEST_ACTION, invocationId))) {
            assertThatThrownBy(() -> registry.register(key)).isInstanceOf(RegistrationRejectedException.class);
            assertThat(other.outcome()).isEqualTo(Outcome.NOT_CANCELLED);
        }
    }

    @Test
    void lostCancelsAndAbandonedEntriesAreBoundedByCapacityAndTtl() {
        AiInvocationCancellationRegistry small = new AiInvocationCancellationRegistry(2, Duration.ofSeconds(60), clock);
        small.register(new Key(tenantA, actor, AiOperation.NEXT_BEST_ACTION, UUID.randomUUID()));
        small.register(new Key(tenantA, actor, AiOperation.NEXT_BEST_ACTION, UUID.randomUUID()));

        assertThatThrownBy(() -> small.register(key)).isInstanceOf(RegistrationRejectedException.class);

        clock.advance(Duration.ofSeconds(61));
        try (Registration registration = small.register(key)) {
            assertThat(small.size()).isEqualTo(1);
            assertThat(registration.outcome()).isEqualTo(Outcome.NOT_CANCELLED);
        }
    }

    @Test
    void cancelRacingCompletionAlwaysYieldsExactlyOneConsistentOutcome() throws Exception {
        int cancelledWins = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                Key raceKey = new Key(tenantA, actor, AiOperation.NEXT_BEST_ACTION, UUID.randomUUID());
                try (Registration registration = registry.register(raceKey)) {
                    CompletableFuture<String> call = new CompletableFuture<>();
                    registration.handle().attach(call);
                    CountDownLatch start = new CountDownLatch(1);
                    AtomicReference<Boolean> completed = new AtomicReference<>();

                    Future<?> canceller = pool.submit(() -> {
                        start.await();
                        registry.cancel(raceKey);
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
    void cancelRacingTheStartOfTheCallNeverLetsAnUncancelledCallThrough() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                Key raceKey = new Key(tenantA, actor, AiOperation.NEXT_BEST_ACTION, UUID.randomUUID());
                try (Registration registration = registry.register(raceKey)) {
                    CompletableFuture<String> call = new CompletableFuture<>();
                    CountDownLatch start = new CountDownLatch(1);
                    Future<?> canceller = pool.submit(() -> {
                        start.await();
                        registry.cancel(raceKey);
                        return null;
                    });
                    Future<?> starter = pool.submit(() -> {
                        start.await();
                        registration.handle().attach(call);
                        return null;
                    });
                    start.countDown();
                    canceller.get(5, TimeUnit.SECONDS);
                    starter.get(5, TimeUnit.SECONDS);

                    assertThat(call).isCancelled();
                    assertThat(registration.outcome()).isEqualTo(Outcome.CANCELLED);
                }
            }
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
