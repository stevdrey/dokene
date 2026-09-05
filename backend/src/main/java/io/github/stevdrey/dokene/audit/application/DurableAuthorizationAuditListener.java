package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.application.AuthorizationAuditListener;
import io.github.stevdrey.dokene.tenant.application.AuthorizationDeniedEvent;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import org.springframework.stereotype.Component;

@Component
public class DurableAuthorizationAuditListener implements AuthorizationAuditListener {
    private final AuditRecorder recorder;

    public DurableAuthorizationAuditListener(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void onAuthorizationDenied(AuthorizationDeniedEvent event) {
        // Attribution is re-derived by the recorder. Foreign resource tenant IDs and free text never cross this port.
        recorder.authorizationDenied(event.requiredPermission(), reason(event.reason()));
    }

    private AuditDenialReason reason(String reason) {
        return switch (reason) {
            case "No active tenant context" -> AuditDenialReason.NO_TENANT_CONTEXT;
            case "Tenant context has no role assigned" -> AuditDenialReason.MISSING_ROLE;
            case "Requested permission is required" -> AuditDenialReason.MISSING_PERMISSION;
            case "Resource tenant ID is required" -> AuditDenialReason.MISSING_RESOURCE_TENANT;
            case "Resource tenant does not match active tenant context" -> AuditDenialReason.CROSS_TENANT_RESOURCE;
            default -> knownDynamicReason(reason);
        };
    }

    private AuditDenialReason knownDynamicReason(String reason) {
        for (TenantMembershipStatus status : TenantMembershipStatus.values()) {
            if (reason.equals("Tenant membership is not active (status: %s)".formatted(status))) {
                return AuditDenialReason.INACTIVE_MEMBERSHIP;
            }
        }
        for (TenantRole role : TenantRole.values()) {
            for (TenantPermission permission : TenantPermission.values()) {
                if (reason.equals("Role %s lacks permission %s".formatted(role, permission))) {
                    return AuditDenialReason.INSUFFICIENT_PERMISSION;
                }
            }
        }
        return AuditDenialReason.UNSPECIFIED;
    }
}
