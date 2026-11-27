package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.testsupport.MutableClock;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkState;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/** Issue 458 (ADR 0037): ledger argument checks, tenant scope and outcome mapping; PostgreSQL behavior is tested apart. */
class FollowUpWorkLedgerTest {
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final FollowUpWorkerId WORKER = new FollowUpWorkerId("instance-a");

    private final FollowUpWorkLedgerRepository repository = mock(FollowUpWorkLedgerRepository.class);
    private final ScopedValueTenantContextProvider contexts = new ScopedValueTenantContextProvider();
    private final TenantId tenantId = TenantId.random();
    private final FollowUpWorkLedger ledger = new FollowUpWorkLedger(repository, contexts,
            new FollowUpWorkProperties(5, Duration.ofSeconds(30), 3), mock(PlatformTransactionManager.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void currentClaimsHoldTheirLease() {
        when(repository.lockClaim(any())).thenAnswer(
                invocation -> Optional.of(invocation.<FollowUpWorkClaim>getArgument(0).leaseExpiresAt()));
    }

    @Test
    void claimsUnderTheTenantScopeWithOneLeaseFromTheClockAfterExhaustingSpentItems() {
        AtomicReference<Optional<TenantId>> scope = new AtomicReference<>();
        FollowUpWorkClaim claim = claim(1);
        when(repository.claim(tenantId, WORKER, NOW, NOW.plusSeconds(30), 3, 2)).thenAnswer(invocation -> {
            scope.set(contexts.currentTenantId());
            return List.of(claim);
        });

        assertThat(ledger.claim(tenantId, WORKER, 2)).containsExactly(claim);
        assertThat(scope.get()).contains(tenantId);
        assertThat(contexts.currentTenantId()).isEmpty();
        verify(repository).reconcileCovered(tenantId, NOW, 5);
        verify(repository).exhaustSpent(tenantId, NOW, 3, 5);
    }

    @Test
    void rejectsClaimLimitsOutsideTheConfiguredBatch() {
        assertThatThrownBy(() -> ledger.claim(tenantId, WORKER, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.claim(tenantId, WORKER, 6)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void refusesToRunInsideAnAmbientTenantScope() {
        assertThatThrownBy(() -> contexts.callWithTenantId(TenantId.random(), () -> ledger.enqueue(tenantId)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> contexts.callWithTenantId(tenantId, () -> ledger.complete(claim(1))))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void enqueuesOneBatchAtTheClockInstantAfterRecordingCoveredCandidates() {
        when(repository.collapseCovered(tenantId, NOW, 5)).thenReturn(2);
        when(repository.enqueue(tenantId, NOW, 5)).thenReturn(4);
        assertThat(ledger.enqueue(tenantId)).isEqualTo(4);
        verify(repository).collapseCovered(tenantId, NOW, 5);
    }

    @Test
    void completesACurrentClaimAndCollapsesTheCustomersOlderCandidates() {
        FollowUpWorkClaim claim = claim(2);
        when(repository.complete(claim, NOW)).thenReturn(true);

        assertThat(ledger.complete(claim)).isEqualTo(FollowUpWorkTransition.COMPLETED);
        verify(repository).collapseInto(claim, NOW, FollowUpWorkLedger.COLLAPSE_LIMIT);
    }

    @Test
    void aStaleCompletionWritesNothingElse() {
        FollowUpWorkClaim claim = claim(1);
        when(repository.complete(claim, NOW)).thenReturn(false);

        assertThat(ledger.complete(claim)).isEqualTo(FollowUpWorkTransition.STALE);
        verify(repository, never()).collapseInto(any(), any(), anyInt());
    }

    @Test
    void mapsFailuresToRetryExhaustionOrStale() {
        FollowUpWorkClaim claim = claim(1);
        Instant retryAt = NOW.plusSeconds(60);
        when(repository.fail(claim, FollowUpWorkFailure.PROCESSING_ERROR, NOW, retryAt, 3))
                .thenReturn(Optional.of(FollowUpWorkState.RETRY_DUE), Optional.of(FollowUpWorkState.EXHAUSTED),
                        Optional.empty());

        assertThat(ledger.fail(claim, FollowUpWorkFailure.PROCESSING_ERROR, retryAt))
                .isEqualTo(FollowUpWorkTransition.RETRY_SCHEDULED);
        assertThat(ledger.fail(claim, FollowUpWorkFailure.PROCESSING_ERROR, retryAt))
                .isEqualTo(FollowUpWorkTransition.EXHAUSTED);
        assertThat(ledger.fail(claim, FollowUpWorkFailure.PROCESSING_ERROR, retryAt))
                .isEqualTo(FollowUpWorkTransition.STALE);
    }

    @Test
    void neverSchedulesARetryForAnAmbiguousOutcomeOrAPastInstant() {
        FollowUpWorkClaim claim = claim(1);
        assertThatThrownBy(() -> ledger.fail(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.fail(claim, FollowUpWorkFailure.LEASE_EXPIRED, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.fail(claim, FollowUpWorkFailure.PROCESSING_ERROR, NOW.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);

        when(repository.exhaust(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN, NOW)).thenReturn(true, false);
        assertThat(ledger.requireRecovery(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .isEqualTo(FollowUpWorkTransition.EXHAUSTED);
        assertThat(ledger.requireRecovery(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .isEqualTo(FollowUpWorkTransition.STALE);
    }

    @Test
    void renewalReturnsTheClaimWithItsNewExpiryOrNothingWhenStale() {
        FollowUpWorkClaim claim = claim(1);
        when(repository.renew(claim, NOW, NOW.plusSeconds(30))).thenReturn(Optional.of(NOW.plusSeconds(30)),
                Optional.empty());

        assertThat(ledger.renew(claim)).hasValueSatisfying(renewed -> {
            assertThat(renewed.leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
            assertThat(renewed.claimToken()).isEqualTo(claim.claimToken());
            assertThat(renewed.attempt()).isEqualTo(claim.attempt());
        });
        assertThat(ledger.renew(claim)).isEmpty();
    }

    @Test
    void judgesTheLeaseAtTheInstantTheRowLockIsHeld() {
        MutableClock clock = new MutableClock(NOW);
        FollowUpWorkLedger waiting = new FollowUpWorkLedger(repository, contexts,
                new FollowUpWorkProperties(5, Duration.ofSeconds(30), 3), mock(PlatformTransactionManager.class),
                clock);
        FollowUpWorkClaim claim = claim(1);
        // The lock waits for another transaction until the lease has expired.
        when(repository.lockClaim(claim)).thenAnswer(invocation -> {
            clock.advance(Duration.ofSeconds(30));
            return Optional.of(claim.leaseExpiresAt());
        });

        assertThat(waiting.complete(claim)).isEqualTo(FollowUpWorkTransition.STALE);
        assertThat(waiting.renew(claim)).isEmpty();
        assertThat(waiting.fail(claim, FollowUpWorkFailure.PROCESSING_ERROR, NOW.plusSeconds(120)))
                .isEqualTo(FollowUpWorkTransition.STALE);
        assertThat(waiting.requireRecovery(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .isEqualTo(FollowUpWorkTransition.STALE);
        verify(repository, never()).complete(any(), any());
        verify(repository, never()).renew(any(), any(), any());
        verify(repository, never()).fail(any(), any(), any(), any(), anyInt());
        verify(repository, never()).exhaust(any(), any(), any());
        verify(repository, never()).collapseInto(any(), any(), anyInt());
    }

    @Test
    void aClaimThatIsNoLongerCurrentOnceLockedIsStale() {
        FollowUpWorkClaim claim = claim(1);
        when(repository.lockClaim(claim)).thenReturn(Optional.empty());

        assertThat(ledger.complete(claim)).isEqualTo(FollowUpWorkTransition.STALE);
        verify(repository, never()).complete(any(), any());
    }

    @Test
    void propertiesDefaultAndBoundTheirValues() {
        FollowUpWorkProperties defaults = new FollowUpWorkProperties(null, null, null);
        assertThat(defaults.batchSize()).isEqualTo(FollowUpWorkProperties.DEFAULT_BATCH_SIZE);
        assertThat(defaults.leaseDuration()).isEqualTo(FollowUpWorkProperties.DEFAULT_LEASE_DURATION);
        assertThat(defaults.maxAttempts()).isEqualTo(FollowUpWorkProperties.DEFAULT_MAX_ATTEMPTS);
        assertThatThrownBy(() -> new FollowUpWorkProperties(0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkProperties(201, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkProperties(null, Duration.ofSeconds(9), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkProperties(null, Duration.ofMinutes(61), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkProperties(null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkProperties(null, null, 21))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void workerIdsAreShortSafeLabels() {
        assertThat(new FollowUpWorkerId("pod-7.dokene:backend_1").value()).isEqualTo("pod-7.dokene:backend_1");
        assertThatThrownBy(() -> new FollowUpWorkerId("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkerId("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkerId("worker one")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkerId("worker\n")).isInstanceOf(IllegalArgumentException.class);
    }

    private FollowUpWorkClaim claim(int attempt) {
        return new FollowUpWorkClaim(UUID.randomUUID(), tenantId, new CustomerId(UUID.randomUUID()),
                LocalDate.parse("2026-11-20"), WORKER, attempt, UUID.randomUUID(), NOW, NOW.plusSeconds(30));
    }
}
