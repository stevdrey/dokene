package io.github.stevdrey.dokene.tenant.api;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.application.IdempotencyConflictException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextAuthorizationException;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService.ProvisionedWorkspace;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.security.AuthenticatedTenantIdentity;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    private final WorkspaceProvisioningService provisioningService;
    private final TenantMembershipDiscovery membershipDiscovery;
    private final TenantContextResolver contextResolver;
    private final AuditRecorder auditRecorder;

    public TenantController(
            WorkspaceProvisioningService provisioningService,
            TenantMembershipDiscovery membershipDiscovery,
            TenantContextResolver contextResolver,
            AuditRecorder auditRecorder
    ) {
        this.provisioningService = Objects.requireNonNull(provisioningService, "Provisioning service is required");
        this.membershipDiscovery = Objects.requireNonNull(membershipDiscovery, "Membership discovery is required");
        this.contextResolver = Objects.requireNonNull(contextResolver, "Context resolver is required");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "Audit recorder is required");
    }

    @GetMapping
    public List<WorkspaceResponse> listWorkspaces(@AuthenticationPrincipal AuthenticatedTenantIdentity identity) {
        requireAuthenticated(identity);
        return membershipDiscovery.findActiveMemberships(identity.identityId()).stream()
                .map(membership -> new WorkspaceResponse(
                        membership.tenantId().value(),
                        membership.tenantDisplayName(),
                        membership.role().name()
                ))
                .toList();
    }

    @PostMapping
    public ResponseEntity<WorkspaceResponse> provisionWorkspace(
            @AuthenticationPrincipal AuthenticatedTenantIdentity identity,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyHeader,
            @RequestBody(required = false) ProvisionWorkspaceRequest request
    ) {
        requireAuthenticated(identity);
        String key = idempotencyHeader != null && !idempotencyHeader.isBlank()
                ? idempotencyHeader
                : (request != null ? request.idempotencyKey() : null);

        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency key is required");
        }
        if (request == null || request.displayName() == null || request.displayName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tenant display name is required");
        }

        ProvisionedWorkspace result = provisioningService.provisionWorkspace(
                identity.identityId(),
                key,
                request.displayName()
        );

        WorkspaceResponse response = new WorkspaceResponse(
                result.tenantId().value(),
                result.displayName(),
                result.role().name()
        );

        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/{tenantId}")
    public WorkspaceResponse getWorkspace(
            @PathVariable UUID tenantId,
            @AuthenticationPrincipal AuthenticatedTenantIdentity identity
    ) {
        requireAuthenticated(identity);
        try {
            TenantContext context = contextResolver.resolve(identity.identityId(), new TenantId(tenantId));
            return membershipDiscovery.findActiveMemberships(identity.identityId()).stream()
                    .filter(membership -> membership.tenantId().equals(context.tenantId()))
                    .findFirst()
                    .map(membership -> new WorkspaceResponse(
                            membership.tenantId().value(),
                            membership.tenantDisplayName(),
                            membership.role().name()
                    ))
                    .orElseThrow(TenantContextAuthorizationException::new);
        } catch (TenantContextAuthorizationException exception) {
            auditRecorder.authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    void handleIdempotencyConflict() {
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    void handleIllegalArgument() {
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    void handleTenantAccessDenied() {
    }

    private void requireAuthenticated(AuthenticatedTenantIdentity identity) {
        if (identity == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    public record ProvisionWorkspaceRequest(String displayName, String idempotencyKey) {
    }

    public record WorkspaceResponse(UUID tenantId, String displayName, String role) {
    }
}
