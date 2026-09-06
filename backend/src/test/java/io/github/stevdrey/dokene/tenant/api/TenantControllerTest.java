package io.github.stevdrey.dokene.tenant.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextAuthorizationException;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery;
import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery.ActiveTenantMembership;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService.ProvisionedWorkspace;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.AuthenticatedTenantIdentity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class TenantControllerTest {

    private WorkspaceProvisioningService provisioningService;
    private TenantMembershipDiscovery membershipDiscovery;
    private TenantContextResolver contextResolver;
    private AuditRecorder auditRecorder;
    private TenantController controller;

    private final IdentityId identityId = new IdentityId(UUID.randomUUID());
    private final AuthenticatedTenantIdentity identity = () -> identityId;

    @BeforeEach
    void setUp() {
        provisioningService = mock();
        membershipDiscovery = mock();
        contextResolver = mock();
        auditRecorder = mock();
        controller = new TenantController(provisioningService, membershipDiscovery, contextResolver, auditRecorder);
    }

    @Test
    void listWorkspacesReturnsActiveMembershipsForAuthenticatedIdentity() {
        TenantId tenantId = TenantId.random();
        ActiveTenantMembership membership = new ActiveTenantMembership(
                tenantId, "Acme Corp", TenantMembershipId.random(), TenantRole.OWNER
        );
        when(membershipDiscovery.findActiveMemberships(identityId)).thenReturn(List.of(membership));

        List<TenantController.WorkspaceResponse> responses = controller.listWorkspaces(identity);

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().tenantId()).isEqualTo(tenantId.value());
        assertThat(responses.getFirst().displayName()).isEqualTo("Acme Corp");
        assertThat(responses.getFirst().role()).isEqualTo("OWNER");
    }

    @Test
    void listWorkspacesRejectsUnauthenticated() {
        assertThatThrownBy(() -> controller.listWorkspaces(null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void provisionWorkspaceReturnsCreatedForNewWorkspace() {
        TenantId tenantId = TenantId.random();
        TenantMembershipId membershipId = TenantMembershipId.random();
        when(provisioningService.provisionWorkspace(identityId, "key-123", "New Workspace"))
                .thenReturn(new ProvisionedWorkspace(tenantId, "New Workspace", membershipId, TenantRole.OWNER, true));

        TenantController.ProvisionWorkspaceRequest request = new TenantController.ProvisionWorkspaceRequest("New Workspace", null);
        ResponseEntity<TenantController.WorkspaceResponse> response = controller.provisionWorkspace(identity, "key-123", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().tenantId()).isEqualTo(tenantId.value());
        assertThat(response.getBody().displayName()).isEqualTo("New Workspace");
        assertThat(response.getBody().role()).isEqualTo("OWNER");
    }

    @Test
    void provisionWorkspaceReturnsOkForIdempotentReplay() {
        TenantId tenantId = TenantId.random();
        TenantMembershipId membershipId = TenantMembershipId.random();
        when(provisioningService.provisionWorkspace(identityId, "key-123", "Existing Workspace"))
                .thenReturn(new ProvisionedWorkspace(tenantId, "Existing Workspace", membershipId, TenantRole.OWNER, false));

        TenantController.ProvisionWorkspaceRequest request = new TenantController.ProvisionWorkspaceRequest("Existing Workspace", "key-123");
        ResponseEntity<TenantController.WorkspaceResponse> response = controller.provisionWorkspace(identity, null, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().tenantId()).isEqualTo(tenantId.value());
    }

    @Test
    void provisionWorkspaceAcceptsMatchingUnicodeNormalizedIdempotencyKeys() {
        TenantId tenantId = TenantId.random();
        TenantMembershipId membershipId = TenantMembershipId.random();
        when(provisioningService.provisionWorkspace(identityId, "key-123", "Existing Workspace"))
                .thenReturn(new ProvisionedWorkspace(tenantId, "Existing Workspace", membershipId, TenantRole.OWNER, false));

        TenantController.ProvisionWorkspaceRequest request = new TenantController.ProvisionWorkspaceRequest(
                "Existing Workspace", "\u00A0key-123 \u00A0"
        );
        ResponseEntity<TenantController.WorkspaceResponse> response = controller.provisionWorkspace(identity, "key-123", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(provisioningService).provisionWorkspace(identityId, "key-123", "Existing Workspace");
    }

    @Test
    void provisionWorkspaceRejectsConflictingHeaderAndBodyIdempotencyKeys() {
        TenantController.ProvisionWorkspaceRequest request = new TenantController.ProvisionWorkspaceRequest(
                "Workspace", "body-key"
        );

        assertThatThrownBy(() -> controller.provisionWorkspace(identity, "header-key", request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verifyNoInteractions(provisioningService);
    }

    @Test
    void provisionWorkspaceRejectsMissingIdempotencyKey() {
        TenantController.ProvisionWorkspaceRequest request = new TenantController.ProvisionWorkspaceRequest("Workspace", null);

        assertThatThrownBy(() -> controller.provisionWorkspace(identity, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void provisionWorkspaceRejectsMissingDisplayName() {
        TenantController.ProvisionWorkspaceRequest request = new TenantController.ProvisionWorkspaceRequest("   ", "key-123");

        assertThatThrownBy(() -> controller.provisionWorkspace(identity, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getWorkspaceReturnsWorkspaceWhenValid() {
        TenantId tenantId = TenantId.random();
        TenantMembershipId membershipId = TenantMembershipId.random();
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.ADMIN);
        when(contextResolver.resolve(identityId, tenantId)).thenReturn(context);
        when(membershipDiscovery.findActiveMemberships(identityId)).thenReturn(List.of(
                new ActiveTenantMembership(tenantId, "Acme", membershipId, TenantRole.ADMIN)
        ));

        TenantController.WorkspaceResponse response = controller.getWorkspace(tenantId.value(), identity);

        assertThat(response.tenantId()).isEqualTo(tenantId.value());
        assertThat(response.displayName()).isEqualTo("Acme");
        assertThat(response.role()).isEqualTo("ADMIN");
    }

    @Test
    void getWorkspaceRejectsUnauthorizedTenantAndAuditsDenial() {
        TenantId tenantId = TenantId.random();
        when(contextResolver.resolve(identityId, tenantId)).thenThrow(new TenantContextAuthorizationException());

        assertThatThrownBy(() -> controller.getWorkspace(tenantId.value(), identity))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        verify(auditRecorder).authorizationDenied(TenantPermission.TENANT_READ, AuditDenialReason.NO_TENANT_CONTEXT);
    }
}
