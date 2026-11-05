package io.github.stevdrey.dokene.audit.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DurableMembershipAuditAdapterTest {

    @Test
    void delegatesRoleChangedToAuditRecorder() {
        AuditRecorder recorder = mock(AuditRecorder.class);
        DurableMembershipAuditAdapter adapter = new DurableMembershipAuditAdapter(recorder);

        TenantMembershipId membershipId = new TenantMembershipId(UUID.randomUUID());
        adapter.roleChanged(membershipId, TenantRole.VIEWER, TenantRole.OPERATOR);

        verify(recorder).membershipRoleChanged(membershipId, TenantRole.VIEWER, TenantRole.OPERATOR);
    }

    @Test
    void delegatesMembershipCreatedToAuditRecorder() {
        AuditRecorder recorder = mock(AuditRecorder.class);
        DurableMembershipAuditAdapter adapter = new DurableMembershipAuditAdapter(recorder);

        TenantMembershipId membershipId = new TenantMembershipId(UUID.randomUUID());
        adapter.membershipCreated(membershipId, TenantRole.OPERATOR);

        verify(recorder).membershipCreated(membershipId, TenantRole.OPERATOR);
    }

    @Test
    void delegatesMembershipRevokedToAuditRecorder() {
        AuditRecorder recorder = mock(AuditRecorder.class);
        DurableMembershipAuditAdapter adapter = new DurableMembershipAuditAdapter(recorder);

        TenantMembershipId membershipId = new TenantMembershipId(UUID.randomUUID());
        adapter.membershipRevoked(membershipId);

        verify(recorder).membershipRevoked(membershipId);
    }

    @Test
    void delegatesInvitationAcceptedAndReactivationToAuditRecorder() {
        AuditRecorder recorder = mock(AuditRecorder.class);
        DurableMembershipAuditAdapter adapter = new DurableMembershipAuditAdapter(recorder);

        TenantMembershipId membershipId = new TenantMembershipId(UUID.randomUUID());
        adapter.invitationAccepted(membershipId, TenantRole.VIEWER);
        adapter.membershipReactivated(membershipId, TenantRole.ADMIN);

        verify(recorder).membershipStatusChanged(membershipId, AuditEventType.MEMBERSHIP_INVITATION_ACCEPTED,
                TenantRole.VIEWER);
        verify(recorder).membershipStatusChanged(membershipId, AuditEventType.MEMBERSHIP_REACTIVATED,
                TenantRole.ADMIN);
    }
}
