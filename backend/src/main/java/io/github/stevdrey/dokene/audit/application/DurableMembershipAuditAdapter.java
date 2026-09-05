package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.tenant.application.MembershipAuditPort;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class DurableMembershipAuditAdapter implements MembershipAuditPort {

    private final AuditRecorder recorder;

    public DurableMembershipAuditAdapter(AuditRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder, "Audit recorder is required");
    }

    @Override
    public void roleChanged(TenantMembershipId membershipId, TenantRole previousRole, TenantRole newRole) {
        recorder.membershipRoleChanged(membershipId, previousRole, newRole);
    }
}
