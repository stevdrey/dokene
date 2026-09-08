package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.UUID;

/** Attribution and event IDs/timestamps are always supplied by the server implementation. */
public interface AuditRecorder {
    void authorizationDenied(TenantPermission permission, AuditDenialReason reason);

    /** Requires an existing business transaction; failure must roll back the state transition. */
    void membershipRoleChanged(TenantMembershipId target, TenantRole previousRole, TenantRole newRole);

    /** Requires an existing business transaction; failure must roll back the state transition. */
    void customerMutated(UUID target, AuditEventType eventType);

    /** Requires an existing business transaction; failure must roll back the state transition. */
    void purchaseMutated(UUID target, AuditEventType eventType);
}
