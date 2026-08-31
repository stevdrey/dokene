package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Audit event captured whenever an authorization check is denied.
 */
public record AuthorizationDeniedEvent(
        Instant timestamp,
        IdentityId identityId,
        TenantId tenantId,
        TenantMembershipId membershipId,
        TenantRole role,
        TenantPermission requiredPermission,
        TenantId resourceTenantId,
        String reason
) {

    public AuthorizationDeniedEvent {
        Objects.requireNonNull(timestamp, "Timestamp is required");
        Objects.requireNonNull(reason, "Reason is required");
    }

    public static AuthorizationDeniedEvent of(
            TenantContext context,
            TenantPermission permission,
            TenantId resourceTenantId,
            String reason,
            Instant timestamp
    ) {
        if (context == null) {
            return new AuthorizationDeniedEvent(
                    timestamp, null, null, null, null, permission, resourceTenantId, reason
            );
        }
        return new AuthorizationDeniedEvent(
                timestamp,
                context.identityId(),
                context.tenantId(),
                context.membershipId(),
                context.role(),
                permission,
                resourceTenantId,
                reason
        );
    }

    public Optional<IdentityId> optionalIdentityId() {
        return Optional.ofNullable(identityId);
    }

    public Optional<TenantId> optionalTenantId() {
        return Optional.ofNullable(tenantId);
    }

    public Optional<TenantMembershipId> optionalMembershipId() {
        return Optional.ofNullable(membershipId);
    }

    public Optional<TenantRole> optionalRole() {
        return Optional.ofNullable(role);
    }

    public Optional<TenantPermission> optionalRequiredPermission() {
        return Optional.ofNullable(requiredPermission);
    }

    public Optional<TenantId> optionalResourceTenantId() {
        return Optional.ofNullable(resourceTenantId);
    }
}
