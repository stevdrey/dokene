package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery.ActiveTenantMembership;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MembershipInvitationServiceTest {

    private final TenantInvitationDirectory directory = mock(TenantInvitationDirectory.class);
    private final TenantContextProvider contexts = new ScopedValueTenantContextProvider();
    private final MembershipAuditPort audit = mock(MembershipAuditPort.class);
    private final MembershipInvitationService service = new MembershipInvitationService(directory, contexts, audit);
    private final IdentityId invitee = new IdentityId(UUID.randomUUID());
    private final TenantId tenant = TenantId.random();

    @Test
    void acceptanceIsAuditedAsTheInviteeUnderTheMembershipItActivated() {
        ActiveTenantMembership accepted = new ActiveTenantMembership(tenant, "Workspace", TenantMembershipId.random(),
                TenantRole.OPERATOR);
        when(directory.accept(invitee, tenant)).thenReturn(Optional.of(accepted));
        AtomicReference<TenantContext> auditedUnder = new AtomicReference<>();
        doAnswer(invocation -> {
            auditedUnder.set(contexts.requireCurrent());
            return null;
        }).when(audit).invitationAccepted(accepted.membershipId(), TenantRole.OPERATOR);

        assertThat(service.acceptInvitation(invitee, tenant)).isEqualTo(accepted);

        assertThat(auditedUnder.get()).isEqualTo(new TenantContext(tenant, invitee, accepted.membershipId(),
                TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE));
        assertThat(contexts.current()).isEmpty();
    }

    @Test
    void acceptingWithoutAPendingInvitationFailsWithoutAudit() {
        when(directory.accept(invitee, tenant)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptInvitation(invitee, tenant))
                .isInstanceOf(MembershipNotFoundException.class);
        verify(audit, never()).invitationAccepted(any(), any());
    }

    @Test
    void invitationsAreNeverHandledInsideATenantContext() {
        TenantContext member = new TenantContext(tenant, invitee, TenantMembershipId.random(), TenantRole.ADMIN);

        contexts.runWithContext(member, () -> {
            assertThatThrownBy(() -> service.acceptInvitation(invitee, tenant))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> service.listPendingInvitations(invitee))
                    .isInstanceOf(IllegalStateException.class);
        });
        verifyNoInteractions(directory, audit);
    }
}
