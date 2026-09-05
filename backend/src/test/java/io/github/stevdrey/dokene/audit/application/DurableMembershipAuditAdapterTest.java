package io.github.stevdrey.dokene.audit.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
}
