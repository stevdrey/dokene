// ABOUTME: Pins how follow-up work attempts are classified and recorded (issue 461, ADR 0039): health gating, backoff on
// ABOUTME: transient failures, exhaustion on permanent ones, recovery on unknown outcomes and exceptions, stale claims.
package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkAttemptResult;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkClaim;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkFailure;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class FollowUpWorkProcessorTest {
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private static final FollowUpWorkerId WORKER = new FollowUpWorkerId("instance-a");

    private final FollowUpWorkLedger ledger = mock(FollowUpWorkLedger.class);
    private final TenantId tenantId = TenantId.random();
    private final FollowUpRetryPolicy retries = new FollowUpRetryPolicy(
            new FollowUpRetryProperties(Duration.ofMinutes(1), Duration.ofMinutes(10), 0.0, 3), () -> 0.0);

    @BeforeEach
    void currentClaimsRenew() {
        when(ledger.renew(any())).thenAnswer(invocation -> Optional.of(invocation.<FollowUpWorkClaim>getArgument(0)));
    }

    @Test
    void neverStartsAHandlerForAClaimThatWentStaleWhileItWaited() {
        FollowUpWorkClaim first = claim(1);
        FollowUpWorkClaim waiting = claim(1);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(first, waiting));
        when(ledger.renew(waiting)).thenReturn(Optional.empty());
        when(ledger.complete(first)).thenReturn(FollowUpWorkTransition.COMPLETED);
        List<FollowUpWorkClaim> handled = new ArrayList<>();

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5, lease -> {
            handled.add(lease.claim());
            return new FollowUpWorkAttemptResult.Completed();
        });

        assertThat(handled).containsExactly(first);
        assertThat(summary.completed()).isEqualTo(1);
        assertThat(summary.stale()).isEqualTo(1);
        verify(ledger, never()).complete(waiting);
        verify(ledger, never()).requireRecovery(any(), any());
    }

    @Test
    void completesWhatTheHandlerFinished() {
        FollowUpWorkClaim claim = claim(1);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(claim));
        when(ledger.complete(claim)).thenReturn(FollowUpWorkTransition.COMPLETED);

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY)
                .process(tenantId, WORKER, 5, lease -> new FollowUpWorkAttemptResult.Completed());

        assertThat(summary).isEqualTo(new FollowUpWorkProcessor.RunSummary(
                FollowUpProviderHealth.Availability.HEALTHY, 1, 1, 0, 0, 0, 0, 0));
    }

    @Test
    void retriesATransientFailureAfterTheBackoffOfItsAttempt() {
        FollowUpWorkClaim claim = claim(3);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(claim));
        when(ledger.fail(claim, FollowUpWorkFailure.PROCESSING_ERROR, NOW.plus(Duration.ofMinutes(4))))
                .thenReturn(FollowUpWorkTransition.RETRY_SCHEDULED);

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5,
                lease -> new FollowUpWorkAttemptResult.Transient(FollowUpWorkFailure.PROCESSING_ERROR));

        assertThat(summary.retryScheduled()).isEqualTo(1);
    }

    @Test
    void countsATransientFailureOnTheLastAttemptAsExhausted() {
        FollowUpWorkClaim claim = claim(3);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(claim));
        when(ledger.fail(any(), any(), any())).thenReturn(FollowUpWorkTransition.EXHAUSTED);

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5,
                lease -> new FollowUpWorkAttemptResult.Transient(FollowUpWorkFailure.PROCESSING_ERROR));

        assertThat(summary.exhausted()).isEqualTo(1);
        assertThat(summary.recoveryRequired()).isZero();
    }

    @Test
    void exhaustsAPermanentFailureWithoutARetry() {
        FollowUpWorkClaim claim = claim(1);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(claim));
        when(ledger.requireRecovery(claim, FollowUpWorkFailure.REJECTED)).thenReturn(FollowUpWorkTransition.EXHAUSTED);

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5,
                lease -> new FollowUpWorkAttemptResult.Permanent(FollowUpWorkFailure.REJECTED));

        assertThat(summary.exhausted()).isEqualTo(1);
        verify(ledger, never()).fail(any(), any(), any());
    }

    @Test
    void sendsAnUnknownOutcomeAndAnyHandlerExceptionToRecoveryNeverToARetry() {
        FollowUpWorkClaim unknown = claim(1);
        FollowUpWorkClaim thrown = claim(1);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(unknown, thrown));
        when(ledger.requireRecovery(any(), any())).thenReturn(FollowUpWorkTransition.EXHAUSTED);

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5, lease -> {
            if (lease.claim() == thrown) {
                throw new IllegalStateException("provider said something about +506 8888 0000");
            }
            return new FollowUpWorkAttemptResult.OutcomeUnknown();
        });

        assertThat(summary.recoveryRequired()).isEqualTo(2);
        verify(ledger).requireRecovery(unknown, FollowUpWorkFailure.OUTCOME_UNKNOWN);
        verify(ledger).requireRecovery(thrown, FollowUpWorkFailure.OUTCOME_UNKNOWN);
        verify(ledger, never()).fail(any(), any(), any());
    }

    @Test
    void releasesTheRestOfTheBatchWithoutAttemptsOnceADependencyIsUnavailable() {
        FollowUpWorkClaim first = claim(1);
        FollowUpWorkClaim second = claim(2);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(first, second));
        when(ledger.fail(any(), any(), any())).thenReturn(FollowUpWorkTransition.RETRY_SCHEDULED);
        AtomicInteger attempted = new AtomicInteger();

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5, lease -> {
            attempted.incrementAndGet();
            return new FollowUpWorkAttemptResult.Transient(FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE);
        });

        assertThat(attempted).hasValue(1);
        assertThat(summary.retryScheduled()).isEqualTo(2);
        verify(ledger).fail(first, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(1)));
        verify(ledger).fail(second, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(2)));
    }

    @Test
    void releasesAClaimWhoseRenewalThrowsAndTheRestOfTheBatchUntried() {
        FollowUpWorkClaim first = claim(1);
        FollowUpWorkClaim unrenewable = claim(3);
        FollowUpWorkClaim waiting = claim(2);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(first, unrenewable, waiting));
        when(ledger.renew(unrenewable)).thenThrow(new IllegalStateException("database unreachable"));
        when(ledger.complete(first)).thenReturn(FollowUpWorkTransition.COMPLETED);
        when(ledger.fail(unrenewable, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(4))))
                .thenReturn(FollowUpWorkTransition.EXHAUSTED);
        when(ledger.fail(waiting, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(2))))
                .thenReturn(FollowUpWorkTransition.RETRY_SCHEDULED);
        List<FollowUpWorkClaim> handled = new ArrayList<>();

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5, lease -> {
            handled.add(lease.claim());
            return new FollowUpWorkAttemptResult.Completed();
        });

        // The claim on its last attempt is exhausted as a safe, requeueable failure, never as an unknown outcome.
        assertThat(handled).containsExactly(first);
        assertThat(summary).isEqualTo(new FollowUpWorkProcessor.RunSummary(
                FollowUpProviderHealth.Availability.HEALTHY, 3, 1, 1, 1, 0, 0, 0));
        verify(ledger, never()).renew(waiting);
        verify(ledger, never()).requireRecovery(any(), any());
    }

    @Test
    void keepsReleasingTheBatchWhenOneUntriedReleaseFails() {
        FollowUpWorkClaim first = claim(1);
        FollowUpWorkClaim unreleasable = claim(1);
        FollowUpWorkClaim waiting = claim(1);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(first, unreleasable, waiting));
        when(ledger.fail(any(), any(), any())).thenReturn(FollowUpWorkTransition.RETRY_SCHEDULED);
        when(ledger.fail(unreleasable, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(1))))
                .thenThrow(new IllegalStateException("database unreachable"));

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5,
                lease -> new FollowUpWorkAttemptResult.Transient(FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE));

        assertThat(summary.claimed()).isEqualTo(3);
        assertThat(summary.retryScheduled()).isEqualTo(2);
        assertThat(summary.abandoned()).isEqualTo(1);
        verify(ledger).fail(waiting, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(1)));
        verify(ledger, never()).requireRecovery(any(), any());
    }

    @Test
    void releasesTheRestOfTheBatchWhenAnOutcomeWriteFails() {
        FollowUpWorkClaim first = claim(1);
        FollowUpWorkClaim second = claim(1);
        FollowUpWorkClaim third = claim(2);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(first, second, third));
        when(ledger.complete(first)).thenThrow(new IllegalStateException("database unreachable"));
        when(ledger.fail(any(), any(), any())).thenReturn(FollowUpWorkTransition.RETRY_SCHEDULED);
        List<FollowUpWorkClaim> handled = new ArrayList<>();

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5, lease -> {
            handled.add(lease.claim());
            return new FollowUpWorkAttemptResult.Completed();
        });

        assertThat(handled).containsExactly(first);
        assertThat(summary).isEqualTo(new FollowUpWorkProcessor.RunSummary(
                FollowUpProviderHealth.Availability.HEALTHY, 3, 0, 2, 0, 0, 0, 1));
        verify(ledger).fail(second, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(1)));
        verify(ledger).fail(third, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(2)));
    }

    @Test
    void recordsAnUnknownOutcomeAndReleasesTheBatchWhenAHandlerThrowsAnError() {
        FollowUpWorkClaim first = claim(1);
        FollowUpWorkClaim waiting = claim(1);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(first, waiting));
        when(ledger.requireRecovery(first, FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .thenReturn(FollowUpWorkTransition.EXHAUSTED);
        when(ledger.fail(any(), any(), any())).thenReturn(FollowUpWorkTransition.RETRY_SCHEDULED);
        FollowUpWorkProcessor processor = processor(FollowUpProviderHealth.Availability.HEALTHY);

        assertThatThrownBy(() -> processor.process(tenantId, WORKER, 5, lease -> {
            throw new StackOverflowError();
        })).isInstanceOf(StackOverflowError.class);

        verify(ledger).requireRecovery(first, FollowUpWorkFailure.OUTCOME_UNKNOWN);
        verify(ledger).fail(waiting, FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE, NOW.plus(Duration.ofMinutes(1)));
        verify(ledger, never()).renew(waiting);
    }

    @Test
    void claimsNothingWhileProvidersAreUnavailable() {
        var summary = processor(FollowUpProviderHealth.Availability.UNAVAILABLE)
                .process(tenantId, WORKER, 5, lease -> new FollowUpWorkAttemptResult.Completed());

        assertThat(summary.deferred()).isTrue();
        assertThat(summary.claimed()).isZero();
        verifyNoInteractions(ledger);
    }

    @Test
    void probesWithOneClaimWhileHealthIsDegradedUnknownMissingOrUnreadable() {
        when(ledger.claim(tenantId, WORKER, 1)).thenReturn(List.of());
        FollowUpWorkHandler handler = lease -> new FollowUpWorkAttemptResult.Completed();

        assertThat(processor(FollowUpProviderHealth.Availability.DEGRADED).process(tenantId, WORKER, 5, handler)
                .availability()).isEqualTo(FollowUpProviderHealth.Availability.DEGRADED);
        assertThat(processor(FollowUpProviderHealth.Availability.UNKNOWN).process(tenantId, WORKER, 5, handler)
                .availability()).isEqualTo(FollowUpProviderHealth.Availability.UNKNOWN);
        assertThat(new FollowUpWorkProcessor(ledger, retries, new StaticListableBeanFactory()
                .getBeanProvider(FollowUpProviderHealth.class), Clock.fixed(NOW, ZoneOffset.UTC))
                .process(tenantId, WORKER, 5, handler).availability())
                .isEqualTo(FollowUpProviderHealth.Availability.UNKNOWN);
        FollowUpProviderHealth broken = tenant -> {
            throw new IllegalStateException("monitor down");
        };
        assertThat(processor(broken).process(tenantId, WORKER, 5, handler).availability())
                .isEqualTo(FollowUpProviderHealth.Availability.UNKNOWN);

        verify(ledger, times(4)).claim(tenantId, WORKER, 1);
        verify(ledger, never()).claim(tenantId, WORKER, 5);
    }

    @Test
    void theHandlerWorksWithItsRenewedClaimAndStopsOnceItIsStale() {
        FollowUpWorkClaim claim = claim(1);
        FollowUpWorkClaim started = claim.withLeaseExpiresAt(NOW.plusSeconds(60));
        FollowUpWorkClaim renewed = claim.withLeaseExpiresAt(NOW.plusSeconds(90));
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(claim));
        when(ledger.renew(claim)).thenReturn(Optional.of(started));
        when(ledger.renew(started)).thenReturn(Optional.of(renewed));
        when(ledger.renew(renewed)).thenReturn(Optional.empty());
        when(ledger.complete(renewed)).thenReturn(FollowUpWorkTransition.STALE);
        List<Boolean> renewals = new ArrayList<>();

        var summary = processor(FollowUpProviderHealth.Availability.HEALTHY).process(tenantId, WORKER, 5, lease -> {
            // The handler starts with the lease renewed just before it.
            assertThat(lease.claim()).isEqualTo(started);
            renewals.add(lease.renew());
            assertThat(lease.claim()).isEqualTo(renewed);
            renewals.add(lease.renew());
            renewals.add(lease.renew());
            return new FollowUpWorkAttemptResult.Completed();
        });

        assertThat(renewals).containsExactly(true, false, false);
        assertThat(summary.stale()).isEqualTo(1);
        verify(ledger).complete(renewed);
        // Once stale, the lease is not renewed again.
        verify(ledger, times(3)).renew(any());
    }

    @Test
    void treatsAMissingResultAsAnUnknownOutcome() {
        FollowUpWorkClaim claim = claim(1);
        when(ledger.claim(tenantId, WORKER, 5)).thenReturn(List.of(claim));
        when(ledger.requireRecovery(claim, FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .thenReturn(FollowUpWorkTransition.EXHAUSTED);

        assertThat(processor(FollowUpProviderHealth.Availability.HEALTHY)
                .process(tenantId, WORKER, 5, lease -> null).recoveryRequired()).isEqualTo(1);
    }

    @Test
    void attemptResultsAcceptOnlyTheirOwnFailureCategories() {
        assertThatThrownBy(() -> new FollowUpWorkAttemptResult.Transient(FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkAttemptResult.Transient(FollowUpWorkFailure.REJECTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkAttemptResult.Transient(FollowUpWorkFailure.LEASE_EXPIRED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkAttemptResult.Permanent(FollowUpWorkFailure.OUTCOME_UNKNOWN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpWorkAttemptResult.Permanent(FollowUpWorkFailure.DEPENDENCY_UNAVAILABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new FollowUpWorkAttemptResult.Permanent(FollowUpWorkFailure.PROCESSING_ERROR).failure())
                .isEqualTo(FollowUpWorkFailure.PROCESSING_ERROR);
    }

    @Test
    void rejectsAnInvalidLimitThroughTheLedger() {
        when(ledger.claim(tenantId, WORKER, 0)).thenThrow(new IllegalArgumentException("limit"));
        assertThatThrownBy(() -> processor(FollowUpProviderHealth.Availability.HEALTHY)
                .process(tenantId, WORKER, 0, lease -> new FollowUpWorkAttemptResult.Completed()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(ledger, never()).complete(any());
    }

    private FollowUpWorkProcessor processor(FollowUpProviderHealth.Availability availability) {
        return processor(tenant -> availability);
    }

    private FollowUpWorkProcessor processor(FollowUpProviderHealth health) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("health", health);
        ObjectProvider<FollowUpProviderHealth> provider = beans.getBeanProvider(FollowUpProviderHealth.class);
        return new FollowUpWorkProcessor(ledger, retries, provider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private FollowUpWorkClaim claim(int attempt) {
        return new FollowUpWorkClaim(UUID.randomUUID(), tenantId, new CustomerId(UUID.randomUUID()),
                LocalDate.of(2026, 11, 27), WORKER, attempt, UUID.randomUUID(), NOW, NOW.plusSeconds(30));
    }
}
