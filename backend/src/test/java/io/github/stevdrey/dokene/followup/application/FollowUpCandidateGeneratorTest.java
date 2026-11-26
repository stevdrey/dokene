package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpCandidate;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class FollowUpCandidateGeneratorTest {
    private static final Instant NOW = Instant.parse("2026-03-08T05:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-03-08");

    private final FollowUpCandidateRepository candidates = mock(FollowUpCandidateRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ScopedValueTenantContextProvider contexts = new ScopedValueTenantContextProvider();
    private final TenantId tenantId = TenantId.random();

    @Test
    void scansUnderTheTenantScopeAtTheClockInstantWithTheConfiguredBatch() {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant(TenantStatus.ACTIVE)));
        AtomicReference<Optional<TenantId>> scope = new AtomicReference<>();
        FollowUpCandidate candidate = candidate(TODAY);
        when(candidates.recordDueCandidates(tenantId, NOW, 5)).thenAnswer(invocation -> {
            scope.set(contexts.currentTenantId());
            return List.of(candidate);
        });

        FollowUpCandidateGeneration result = generator(5).generate(tenantId);

        assertThat(scope.get()).contains(tenantId);
        assertThat(contexts.currentTenantId()).isEmpty();
        assertThat(result.outcome()).isEqualTo(FollowUpCandidateGeneration.Outcome.SCANNED);
        assertThat(result.evaluatedAt()).isEqualTo(NOW);
        assertThat(result.recorded()).containsExactly(candidate);
        assertThat(result.batchFull()).isFalse();
    }

    @Test
    void reportsAFullBatchSoTheCallerKnowsMoreCyclesMayRemain() {
        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant(TenantStatus.ACTIVE)));
        when(candidates.recordDueCandidates(tenantId, NOW, 1)).thenReturn(List.of(candidate(TODAY)));

        assertThat(generator(1).generate(tenantId).batchFull()).isTrue();
    }

    @Test
    void skipsMissingAndInactiveTenantsWithoutScanning() {
        when(tenants.findById(tenantId)).thenReturn(Optional.empty());
        assertThat(generator(5).generate(tenantId).outcome())
                .isEqualTo(FollowUpCandidateGeneration.Outcome.TENANT_INACTIVE);

        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant(TenantStatus.SUSPENDED)));
        FollowUpCandidateGeneration suspended = generator(5).generate(tenantId);
        assertThat(suspended.outcome()).isEqualTo(FollowUpCandidateGeneration.Outcome.TENANT_INACTIVE);
        assertThat(suspended.recorded()).isEmpty();

        when(tenants.findById(tenantId)).thenReturn(Optional.of(tenant(TenantStatus.ARCHIVED)));
        assertThat(generator(5).generate(tenantId).outcome())
                .isEqualTo(FollowUpCandidateGeneration.Outcome.TENANT_INACTIVE);
        verify(candidates, never()).recordDueCandidates(any(), any(), anyInt());
    }

    @Test
    void refusesToRunInsideAnAmbientTenantScope() {
        TenantId other = TenantId.random();
        assertThatThrownBy(() -> contexts.callWithTenantId(other, () -> generator(5).generate(tenantId)))
                .isInstanceOf(IllegalStateException.class);
        verify(candidates, never()).recordDueCandidates(any(), any(), anyInt());
    }

    @Test
    void boundsTheBatchSize() {
        assertThat(new FollowUpCandidateProperties(null).batchSize())
                .isEqualTo(FollowUpCandidateProperties.DEFAULT_BATCH_SIZE);
        assertThatThrownBy(() -> new FollowUpCandidateProperties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FollowUpCandidateProperties(FollowUpCandidateProperties.MAX_BATCH_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void candidatesNeedACycleAnchorAndAReachedDueDate() {
        assertThat(candidate(TODAY).statusWhenGenerated()).isEqualTo(FollowUpStatus.DUE);
        assertThat(candidate(TODAY.minusDays(1)).statusWhenGenerated()).isEqualTo(FollowUpStatus.OVERDUE);
        assertThatThrownBy(() -> candidate(TODAY.plusDays(1))).isInstanceOf(IllegalArgumentException.class);
        for (FollowUpTimingSource source : List.of(FollowUpTimingSource.SNOOZE, FollowUpTimingSource.NONE)) {
            assertThatThrownBy(() -> new FollowUpCandidate(UUID.randomUUID(), tenantId,
                    new CustomerId(UUID.randomUUID()), TODAY, source, 30, 1, TODAY, NOW))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new FollowUpCandidate(UUID.randomUUID(), tenantId,
                new CustomerId(UUID.randomUUID()), TODAY, FollowUpTimingSource.LAST_PURCHASE, 0, 1, TODAY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private FollowUpCandidateGenerator generator(int batchSize) {
        return new FollowUpCandidateGenerator(candidates, tenants, contexts,
                new FollowUpCandidateProperties(batchSize), transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Tenant tenant(TenantStatus status) {
        return Tenant.restore(tenantId, "Tenant", status, NOW, NOW, 0);
    }

    private FollowUpCandidate candidate(LocalDate dueDate) {
        return new FollowUpCandidate(UUID.randomUUID(), tenantId, new CustomerId(UUID.randomUUID()), dueDate,
                FollowUpTimingSource.LAST_PURCHASE, 30, 1, TODAY, NOW);
    }
}
