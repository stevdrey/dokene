package io.github.stevdrey.dokene.audit.domain;

import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.Objects;

/** Closed, scalar-only metadata. Never accepts free text or arbitrary payloads. */
public sealed interface AuditMetadata {

    record AuthorizationDenied(TenantPermission permission, AuditDenialReason reason) implements AuditMetadata {
        public AuthorizationDenied {
            Objects.requireNonNull(reason, "Denial reason is required");
        }
    }

    record MembershipRoleChanged(TenantRole previousRole, TenantRole newRole) implements AuditMetadata {
        public MembershipRoleChanged {
            Objects.requireNonNull(previousRole, "Previous role is required");
            Objects.requireNonNull(newRole, "New role is required");
            if (previousRole == newRole || previousRole == TenantRole.OWNER || newRole == TenantRole.OWNER) {
                throw new IllegalArgumentException("Unsupported role transition");
            }
        }
    }
}
