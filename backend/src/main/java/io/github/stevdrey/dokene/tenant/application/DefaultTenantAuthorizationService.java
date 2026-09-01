package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRolePermissions;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link TenantAuthorizationService} that fails closed.
 */
@Service
public class DefaultTenantAuthorizationService implements TenantAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantAuthorizationService.class);

    private final TenantContextProvider tenantContextProvider;
    private final AuthorizationAuditListener auditListener;
    private final Clock clock;

    public DefaultTenantAuthorizationService(
            TenantContextProvider tenantContextProvider,
            AuthorizationAuditListener auditListener,
            Clock clock
    ) {
        this.tenantContextProvider = Objects.requireNonNull(tenantContextProvider, "Tenant context provider is required");
        this.auditListener = Objects.requireNonNull(auditListener, "Authorization audit listener is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public AuthorizationDecision evaluate(TenantPermission permission) {
        return evaluate(tenantContextProvider.current().orElse(null), permission, null, false);
    }

    @Override
    public AuthorizationDecision evaluate(TenantContext context, TenantPermission permission) {
        return evaluate(context, permission, null, false);
    }

    @Override
    public AuthorizationDecision evaluate(TenantContext context, TenantPermission permission, TenantScopedResource resource) {
        TenantId resourceTenantId = resource != null ? resource.tenantId() : null;
        return evaluate(context, permission, resourceTenantId, true);
    }

    @Override
    public AuthorizationDecision evaluate(TenantContext context, TenantPermission permission, TenantId resourceTenantId) {
        return evaluate(context, permission, resourceTenantId, true);
    }

    private AuthorizationDecision evaluate(
            TenantContext context,
            TenantPermission permission,
            TenantId resourceTenantId,
            boolean requiresResourceOwnership
    ) {
        if (context == null) {
            return AuthorizationDecision.deny("No active tenant context");
        }
        if (context.status() != TenantMembershipStatus.ACTIVE) {
            return AuthorizationDecision.deny("Tenant membership is not active (status: %s)".formatted(context.status()));
        }
        if (context.role() == null) {
            return AuthorizationDecision.deny("Tenant context has no role assigned");
        }
        if (permission == null) {
            return AuthorizationDecision.deny("Requested permission is required");
        }
        if (requiresResourceOwnership && resourceTenantId == null) {
            return AuthorizationDecision.deny("Resource tenant ID is required");
        }
        if (resourceTenantId != null && !context.tenantId().equals(resourceTenantId)) {
            return AuthorizationDecision.deny("Resource tenant does not match active tenant context");
        }
        if (!TenantRolePermissions.hasPermission(context.role(), permission)) {
            return AuthorizationDecision.deny("Role %s lacks permission %s".formatted(context.role(), permission));
        }
        return AuthorizationDecision.allow();
    }

    @Override
    public void requirePermission(TenantPermission permission) {
        TenantContext context = tenantContextProvider.current().orElse(null);
        AuthorizationDecision decision = evaluate(context, permission, null, false);
        if (!decision.isAllowed()) {
            auditAndThrow(context, permission, null, decision.rejectionReason().orElse("Denied"));
        }
    }

    @Override
    public void requireResourceAccess(TenantPermission permission, TenantScopedResource resource) {
        TenantId resourceTenantId = resource != null ? resource.tenantId() : null;
        requireResourceAccess(permission, resourceTenantId);
    }

    @Override
    public void requireResourceAccess(TenantPermission permission, TenantId resourceTenantId) {
        TenantContext context = tenantContextProvider.current().orElse(null);
        AuthorizationDecision decision = evaluate(context, permission, resourceTenantId);
        if (!decision.isAllowed()) {
            auditAndThrow(context, permission, resourceTenantId, decision.rejectionReason().orElse("Denied"));
        }
    }

    @Override
    public boolean hasPermission(TenantPermission permission) {
        TenantContext context = tenantContextProvider.current().orElse(null);
        return evaluateAndAudit(context, permission, null, false).isAllowed();
    }

    @Override
    public boolean hasResourceAccess(TenantPermission permission, TenantScopedResource resource) {
        TenantContext context = tenantContextProvider.current().orElse(null);
        TenantId resourceTenantId = resource != null ? resource.tenantId() : null;
        return evaluateAndAudit(context, permission, resourceTenantId, true).isAllowed();
    }

    @Override
    public boolean hasResourceAccess(TenantPermission permission, TenantId resourceTenantId) {
        TenantContext context = tenantContextProvider.current().orElse(null);
        return evaluateAndAudit(context, permission, resourceTenantId, true).isAllowed();
    }

    private AuthorizationDecision evaluateAndAudit(
            TenantContext context,
            TenantPermission permission,
            TenantId resourceTenantId,
            boolean requiresResourceOwnership
    ) {
        AuthorizationDecision decision = evaluate(context, permission, resourceTenantId, requiresResourceOwnership);
        if (!decision.isAllowed()) {
            auditDenial(context, permission, resourceTenantId, decision.rejectionReason().orElse("Denied"));
        }
        return decision;
    }

    private void auditAndThrow(
            TenantContext context,
            TenantPermission permission,
            TenantId resourceTenantId,
            String reason
    ) {
        auditDenial(context, permission, resourceTenantId, reason);
        throw new TenantAccessDeniedException();
    }

    private void auditDenial(
            TenantContext context,
            TenantPermission permission,
            TenantId resourceTenantId,
            String reason
    ) {
        Instant now = clock.instant();
        AuthorizationDeniedEvent event = AuthorizationDeniedEvent.of(context, permission, resourceTenantId, reason, now);
        try {
            auditListener.onAuthorizationDenied(event);
        } catch (Exception exception) {
            log.warn("Failed to dispatch authorization denied audit event", exception);
        }
    }
}
