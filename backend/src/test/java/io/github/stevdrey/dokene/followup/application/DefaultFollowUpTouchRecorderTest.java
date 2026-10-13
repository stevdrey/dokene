// ABOUTME: Unit tests for DefaultFollowUpTouchRecorder: the tenant calendar decides the anchor date, never the input.
// ABOUTME: Covers the date rule of AC-9 of spec 0001 and the fail closed tenant lookup.
package io.github.stevdrey.dokene.followup.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultFollowUpTouchRecorderTest {
    private final FollowUpPolicyRepository policies = mock(FollowUpPolicyRepository.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final DefaultFollowUpTouchRecorder recorder = new DefaultFollowUpTouchRecorder(policies, contexts);
    private final TenantId tenantId = TenantId.random();
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());

    @Test
    void convertsTheSentInstantToTheTenantCalendarDate() {
        when(contexts.currentTenantId()).thenReturn(Optional.of(tenantId));
        when(policies.tenantPolicy(tenantId)).thenReturn(new TenantFollowUpPolicy(tenantId, 30,
                ZoneId.of("America/Santiago"), 0));

        recorder.recordOutboundMessage(customerId, Instant.parse("2026-10-09T02:30:00Z"));

        verify(policies).recordOutboundMessage(tenantId, customerId, LocalDate.of(2026, 10, 8));
    }

    @Test
    void usesTheUtcDateWhenTheTenantRunsOnUtc() {
        when(contexts.currentTenantId()).thenReturn(Optional.of(tenantId));
        when(policies.tenantPolicy(tenantId)).thenReturn(new TenantFollowUpPolicy(tenantId, 30, ZoneId.of("UTC"), 0));

        recorder.recordOutboundMessage(customerId, Instant.parse("2026-10-09T02:30:00Z"));

        verify(policies).recordOutboundMessage(tenantId, customerId, LocalDate.of(2026, 10, 9));
    }

    @Test
    void failsClosedWithoutATenantContextAndWritesNothing() {
        when(contexts.currentTenantId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.recordOutboundMessage(customerId, Instant.parse("2026-10-09T02:30:00Z")))
                .isInstanceOf(TenantContextUnavailableException.class);

        verify(policies, never()).recordOutboundMessage(any(), any(), any());
    }

    @Test
    void rejectsMissingArguments() {
        assertThatThrownBy(() -> recorder.recordOutboundMessage(null, Instant.EPOCH)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> recorder.recordOutboundMessage(customerId, null)).isInstanceOf(NullPointerException.class);
        verify(policies, never()).recordOutboundMessage(any(), any(), any());
    }
}
