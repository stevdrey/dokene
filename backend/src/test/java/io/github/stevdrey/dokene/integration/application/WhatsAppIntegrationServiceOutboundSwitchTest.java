// ABOUTME: Unit tests for WhatsAppIntegrationService.setOutboundEnabled, the only write path of the tenant kill switch.
// ABOUTME: Covers AC-3, AC-5 and AC-6 of spec 0008 with the repository, audit port and contexts mocked at the boundary.
package io.github.stevdrey.dokene.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationService.IntegrationView;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

class WhatsAppIntegrationServiceOutboundSwitchTest {
    private static final Instant CREATED = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-19T09:30:00Z");

    private final WhatsAppIntegrationRepository repository = mock(WhatsAppIntegrationRepository.class);
    private final IntegrationAuditPort audit = mock(IntegrationAuditPort.class);
    private final TenantAuthorizationService authorization = mock(TenantAuthorizationService.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final TenantId tenantId = TenantId.random();
    private final WhatsAppIntegrationService service = new WhatsAppIntegrationService(repository, audit,
            authorization, contexts, mock(PhoneNumberOwnershipVerifier.class), mock(MappingRejectionLimiter.class),
            Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));

    private WhatsAppIntegration stored;

    @BeforeEach
    void bindAnAdminOfTheTenant() {
        when(contexts.requireCurrent()).thenReturn(new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
                TenantMembershipId.random(), TenantRole.OWNER));
        stored = WhatsAppIntegration.create(tenantId, "100000000000001", "200000000000001", "+50688887777", true,
                "wa-token", CREATED).withVersion(3);
        when(repository.findByTenant(tenantId)).thenReturn(Optional.of(stored));
    }

    /** covers: AC-5 */
    @Test
    void requiresIntegrationManageBeforeTouchingTheRow() {
        doThrow(new TenantAccessDeniedException("Forbidden")).when(authorization)
                .requirePermission(TenantPermission.INTEGRATION_MANAGE);

        assertThatThrownBy(() -> service.setOutboundEnabled(3, true))
                .isInstanceOf(TenantAccessDeniedException.class);

        verifyNoInteractions(repository, audit);
    }

    /** covers: AC-5 */
    @Test
    void failsClosedWithoutATenantContext() {
        when(contexts.requireCurrent()).thenThrow(new TenantContextUnavailableException());

        assertThatThrownBy(() -> service.setOutboundEnabled(3, true))
                .isInstanceOf(TenantContextUnavailableException.class);

        verifyNoInteractions(repository, audit);
    }

    /** covers: AC-5 */
    @Test
    void readsTheRowOfTheContextTenantAndAnswersNotFoundWhenThereIsNone() {
        when(repository.findByTenant(tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setOutboundEnabled(0, true))
                .isInstanceOf(IntegrationRefusedException.class)
                .extracting(refusal -> ((IntegrationRefusedException) refusal).code())
                .isEqualTo(MessagingErrorCode.NOT_FOUND);

        verify(repository, never()).update(any(), anyLong());
        verifyNoInteractions(audit);
    }

    /** covers: AC-5 */
    @Test
    void aMismatchedExpectedVersionIsStaleAndWritesNothing() {
        assertThatThrownBy(() -> service.setOutboundEnabled(2, true))
                .isInstanceOf(IntegrationRefusedException.class)
                .extracting(refusal -> ((IntegrationRefusedException) refusal).code())
                .isEqualTo(MessagingErrorCode.STALE_VERSION);

        verify(repository, never()).update(any(), anyLong());
        verifyNoInteractions(audit);
    }

    /** covers: AC-5, AC-6 */
    @Test
    void aRowThatMovedBetweenTheReadAndTheWriteIsStaleAndIsNotAudited() {
        when(repository.update(any(), eq(3L))).thenReturn(OptionalLong.empty());

        assertThatThrownBy(() -> service.setOutboundEnabled(3, true))
                .isInstanceOf(IntegrationRefusedException.class)
                .extracting(refusal -> ((IntegrationRefusedException) refusal).code())
                .isEqualTo(MessagingErrorCode.STALE_VERSION);

        verifyNoInteractions(audit);
    }

    /** covers: AC-5, AC-6 */
    @Test
    void statingTheStoredValueWritesNothingAndAuditsNothing() {
        IntegrationView view = service.setOutboundEnabled(3, false);

        assertThat(view.integration()).isSameAs(stored);
        assertThat(view.integration().outboundEnabled()).isFalse();
        verify(repository, never()).update(any(), anyLong());
        verifyNoInteractions(audit);
    }

    /** covers: AC-5, AC-6 */
    @Test
    void aRealFlipWritesOnlyTheSwitchAndTheServerTimeThenAuditsTheNewValue() {
        when(repository.update(any(), eq(3L))).thenReturn(OptionalLong.of(4));

        IntegrationView view = service.setOutboundEnabled(3, true);

        ArgumentCaptor<WhatsAppIntegration> written = ArgumentCaptor.forClass(WhatsAppIntegration.class);
        verify(repository).update(written.capture(), eq(3L));
        WhatsAppIntegration next = written.getValue();
        assertThat(next.outboundEnabled()).isTrue();
        assertThat(next.updatedAt()).isEqualTo(NOW);
        assertThat(next.createdAt()).isEqualTo(CREATED);
        assertThat(next.enabled()).isEqualTo(stored.enabled());
        assertThat(next.phoneNumberId()).isEqualTo(stored.phoneNumberId());
        assertThat(next.businessAccountId()).isEqualTo(stored.businessAccountId());
        assertThat(next.secretRef()).isEqualTo(stored.secretRef());
        assertThat(next.credentialRevision()).isEqualTo(stored.credentialRevision());
        assertThat(next.ownershipVerifiedAt()).isEqualTo(stored.ownershipVerifiedAt());

        assertThat(view.integration().outboundEnabled()).isTrue();
        assertThat(view.integration().version()).isEqualTo(4);
        assertThat(view.integration().id()).isEqualTo(stored.id());

        InOrder order = inOrder(repository, audit);
        order.verify(repository).update(any(), eq(3L));
        order.verify(audit).outboundSwitchChanged(stored.id(), true);
        verify(audit, never()).updated(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    /** covers: AC-5, AC-6 */
    @Test
    void switchingOffIsAuditedWithTheNewValueFalse() {
        WhatsAppIntegration on = stored.withOutboundEnabled(true, CREATED);
        when(repository.findByTenant(tenantId)).thenReturn(Optional.of(on));
        when(repository.update(any(), eq(3L))).thenReturn(OptionalLong.of(4));

        IntegrationView view = service.setOutboundEnabled(3, false);

        assertThat(view.integration().outboundEnabled()).isFalse();
        verify(audit).outboundSwitchChanged(on.id(), false);
    }

    /** covers: AC-3, AC-5 */
    @Test
    void theSwitchIsIndependentOfTheEnabledFlagAndOfTheOwnershipProof() {
        WhatsAppIntegration disabledAndUnproven = WhatsAppIntegration.create(tenantId, "100000000000001",
                "200000000000001", "+50688887777", false, "wa-token", CREATED).withVersion(7);
        when(repository.findByTenant(tenantId)).thenReturn(Optional.of(disabledAndUnproven));
        when(repository.update(any(), eq(7L))).thenReturn(OptionalLong.of(8));

        IntegrationView view = service.setOutboundEnabled(7, true);

        assertThat(view.integration().outboundEnabled()).isTrue();
        assertThat(view.integration().enabled()).isFalse();
        assertThat(view.webhookVerified()).isFalse();
        verify(audit).outboundSwitchChanged(disabledAndUnproven.id(), true);
    }

    /** covers: AC-6 */
    @Test
    void anAuditFailurePropagatesSoTheTransactionRollsTheFlipBack() {
        when(repository.update(any(), eq(3L))).thenReturn(OptionalLong.of(4));
        doThrow(new IllegalStateException("audit down")).when(audit).outboundSwitchChanged(stored.id(), true);

        assertThatThrownBy(() -> service.setOutboundEnabled(3, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit down");
    }
}
