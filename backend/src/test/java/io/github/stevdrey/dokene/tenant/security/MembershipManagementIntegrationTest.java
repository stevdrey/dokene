package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class MembershipManagementIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    private MockMvc mvc;
    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private AuditRequestFilter auditRequestFilter;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantMembershipRepository memberships;
    @Autowired private TenantContextProvider contexts;
    @Autowired private AuditReader auditReader;
    @Autowired private AuditExecutionContext execution;

    private Tenant tenant;
    private IdentityId owner;
    private IdentityId operator;
    private IdentityId viewer;
    private IdentityId foreignUser;
    private TenantMembership ownerMembership;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenant = seedTenant(tenants, contexts, "Membership test " + UUID.randomUUID(), now);
        owner = new IdentityId(UUID.randomUUID());
        operator = new IdentityId(UUID.randomUUID());
        viewer = new IdentityId(UUID.randomUUID());
        foreignUser = new IdentityId(UUID.randomUUID());

        ownerMembership = seedMembership(memberships, contexts, tenant.id(), owner, TenantRole.OWNER, now);
        seedMembership(memberships, contexts, tenant.id(), operator, TenantRole.OPERATOR, now);
        seedMembership(memberships, contexts, tenant.id(), viewer, TenantRole.VIEWER, now);
    }

    @Test
    void allRolesCanListMembershipsWithinTenantBoundary() throws Exception {
        mvc.perform(get("/api/memberships")
                        .with(user(owner))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(get("/api/memberships")
                        .with(user(operator))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mvc.perform(get("/api/memberships")
                        .with(user(viewer))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void foreignUserCannotAccessMemberships() throws Exception {
        mvc.perform(get("/api/memberships")
                        .with(user(foreignUser))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanAddMembersWithOperatorOrViewerRole() throws Exception {
        IdentityId newIdentity = new IdentityId(UUID.randomUUID());

        mvc.perform(post("/api/memberships")
                        .with(user(owner))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "OPERATOR"
                                }
                                """.formatted(newIdentity.value())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.identityId").value(newIdentity.value().toString()))
                .andExpect(jsonPath("$.role").value("OPERATOR"))
                .andExpect(jsonPath("$.status").value("INVITED"));

        try (var connection = TenantSecurityIntegrationFixture.migrationConnection();
             var statement = connection.prepareStatement(
                     "SELECT event_type, new_role, outcome FROM dokene.audit_events WHERE tenant_id = ? AND event_type = 'MEMBERSHIP_CREATED'")) {
            statement.setObject(1, tenant.id().value());
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("new_role")).isEqualTo("OPERATOR");
                assertThat(rs.getString("outcome")).isEqualTo("SUCCESS");
            }
        }
    }

    @Test
    void operatorInvitingOwnerGetsForbiddenNotBadRequest() throws Exception {
        IdentityId newIdentity = new IdentityId(UUID.randomUUID());

        mvc.perform(post("/api/memberships")
                        .with(user(operator))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "OWNER"
                                }
                                """.formatted(newIdentity.value())))
                .andExpect(status().isForbidden());
    }

    @Test
    void cannotAssignOwnerRoleViaMembershipInvitation() throws Exception {
        IdentityId newIdentity = new IdentityId(UUID.randomUUID());

        mvc.perform(post("/api/memberships")
                        .with(user(owner))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "OWNER"
                                }
                                """.formatted(newIdentity.value())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateMembershipInvitationIsRejected() throws Exception {
        mvc.perform(post("/api/memberships")
                        .with(user(owner))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "OPERATOR"
                                }
                                """.formatted(operator.value())))
                .andExpect(status().isConflict());
    }

    @Test
    void operatorAndViewerCannotAddMembers() throws Exception {
        IdentityId newIdentity = new IdentityId(UUID.randomUUID());

        mvc.perform(post("/api/memberships")
                        .with(user(operator))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "OPERATOR"
                                }
                                """.formatted(newIdentity.value())))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/memberships")
                        .with(user(viewer))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "identityId": "%s",
                                  "role": "VIEWER"
                                }
                                """.formatted(newIdentity.value())))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanChangeMemberRole() throws Exception {
        mvc.perform(put("/api/memberships/{identityId}/role", operator.value())
                        .with(user(owner))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "VIEWER"
                                }
                                """))
                .andExpect(status().isNoContent());

        // Verify updated role in list
        mvc.perform(get("/api/memberships")
                        .with(user(owner))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.identityId == '%s')].role".formatted(operator.value()))
                        .value("VIEWER"));
    }

    @Test
    void operatorAndViewerCannotChangeRoles() throws Exception {
        mvc.perform(put("/api/memberships/{identityId}/role", viewer.value())
                        .with(user(operator))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "OPERATOR"
                                }
                                """))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/memberships/{identityId}/role", operator.value())
                        .with(user(viewer))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "VIEWER"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void ownerCanRevokeMember() throws Exception {
        mvc.perform(delete("/api/memberships/{identityId}", viewer.value())
                        .with(user(owner))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isNoContent());

        // Verify status changed to REVOKED
        mvc.perform(get("/api/memberships")
                        .with(user(owner))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.identityId == '%s')].status".formatted(viewer.value()))
                        .value("REVOKED"));

        try (var connection = TenantSecurityIntegrationFixture.migrationConnection();
             var statement = connection.prepareStatement(
                     "SELECT event_type, outcome FROM dokene.audit_events WHERE tenant_id = ? AND event_type = 'MEMBERSHIP_REVOKED'")) {
            statement.setObject(1, tenant.id().value());
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("outcome")).isEqualTo("SUCCESS");
            }
        }
    }

    @Test
    void cannotRevokeOwnerMembership() throws Exception {
        mvc.perform(delete("/api/memberships/{identityId}", owner.value())
                        .with(user(owner))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void operatorAndViewerCannotRevokeMembers() throws Exception {
        mvc.perform(delete("/api/memberships/{identityId}", viewer.value())
                        .with(user(operator))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/memberships/{identityId}", operator.value())
                        .with(user(viewer))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isForbidden());
    }

    @Test
    void absentMembershipReturnsNotFoundOnUpdateOrRevoke() throws Exception {
        UUID absentIdentity = UUID.randomUUID();

        mvc.perform(put("/api/memberships/{identityId}/role", absentIdentity)
                        .with(user(owner))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "role": "OPERATOR"
                                }
                                """))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/memberships/{identityId}", absentIdentity)
                        .with(user(owner))
                        .with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isNotFound());
    }

    @Test
    void invitedIdentityCannotEnterTheWorkspaceUntilItAccepts() throws Exception {
        IdentityId invitee = new IdentityId(UUID.randomUUID());
        String membershipId = invite(invitee, "VIEWER");

        assertCannotEnter(invitee);
        mvc.perform(get("/api/tenants/invitations").with(user(invitee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].tenantId").value(tenant.id().value().toString()))
                .andExpect(jsonPath("$[0].displayName").value(tenant.displayName()))
                .andExpect(jsonPath("$[0].role").value("VIEWER"))
                .andExpect(jsonPath("$[0].invitedAt").isNotEmpty());
        // Nobody else sees the invitation, including members of the tenant.
        mvc.perform(get("/api/tenants/invitations").with(user(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        accept(invitee)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant.id().value().toString()))
                .andExpect(jsonPath("$.role").value("VIEWER"));

        mvc.perform(get("/api/tenants").with(user(invitee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tenantId == '%s')].role".formatted(tenant.id().value())).value("VIEWER"));
        mvc.perform(get("/api/memberships").with(user(invitee)).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.identityId == '%s')].status".formatted(invitee.value())).value("ACTIVE"));
        mvc.perform(get("/api/tenants/invitations").with(user(invitee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        accept(invitee).andExpect(status().isNotFound());

        assertMembershipEvent("MEMBERSHIP_INVITATION_ACCEPTED", membershipId, invitee, "VIEWER");
    }

    @Test
    void onlyTheInviteeCanAcceptAndARevokedInvitationCannotBeAccepted() throws Exception {
        IdentityId invitee = new IdentityId(UUID.randomUUID());
        invite(invitee, "OPERATOR");

        accept(foreignUser).andExpect(status().isNotFound());
        accept(owner).andExpect(status().isNotFound());
        mvc.perform(post("/api/tenants/{tenantId}/invitation/acceptance", UUID.randomUUID())
                        .with(user(invitee)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/tenants/{tenantId}/invitation/acceptance", tenant.id().value())
                        .with(user(invitee)))
                .andExpect(status().isForbidden());
        assertCannotEnter(invitee);

        mvc.perform(delete("/api/memberships/{identityId}", invitee.value())
                        .with(user(owner)).with(csrf()).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isNoContent());
        accept(invitee).andExpect(status().isNotFound());
        mvc.perform(get("/api/tenants/invitations").with(user(invitee)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        assertCannotEnter(invitee);
    }

    @Test
    void revokedMemberIsReactivatedAsAnInvitationAndMustAcceptAgain() throws Exception {
        String viewerMembershipId = membershipId(viewer);
        mvc.perform(delete("/api/memberships/{identityId}", viewer.value())
                        .with(user(owner)).with(csrf()).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isNoContent());
        assertCannotEnter(viewer);
        // Re-inviting stays a conflict; the recovery is the explicit reactivation.
        mvc.perform(post("/api/memberships")
                        .with(user(owner)).with(csrf()).header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityId\":\"%s\",\"role\":\"VIEWER\"}".formatted(viewer.value())))
                .andExpect(status().isConflict());

        reactivate(owner, viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(viewerMembershipId))
                .andExpect(jsonPath("$.role").value("VIEWER"))
                .andExpect(jsonPath("$.status").value("INVITED"));
        assertCannotEnter(viewer);
        reactivate(owner, viewer).andExpect(status().isConflict());

        accept(viewer).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"));
        mvc.perform(get("/api/memberships").with(user(viewer)).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk());

        assertMembershipEvent("MEMBERSHIP_REACTIVATED", viewerMembershipId, owner, "VIEWER");
        assertMembershipEvent("MEMBERSHIP_INVITATION_ACCEPTED", viewerMembershipId, viewer, "VIEWER");
        List<AuditEventType> readBack = new ArrayList<>();
        execution.runWithCorrelation(UUID.randomUUID(), () -> contexts.runWithContext(
                TenantSecurityIntegrationFixture.context(ownerMembership),
                () -> auditReader.read().events().forEach(event -> readBack.add(event.type()))));
        assertThat(readBack).contains(AuditEventType.MEMBERSHIP_REACTIVATED,
                AuditEventType.MEMBERSHIP_INVITATION_ACCEPTED);
    }

    @Test
    void reactivationIsAuthorizedAndLimitedToRevokedNonOwnerMemberships() throws Exception {
        mvc.perform(delete("/api/memberships/{identityId}", viewer.value())
                        .with(user(owner)).with(csrf()).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isNoContent());

        reactivate(operator, viewer).andExpect(status().isForbidden());
        reactivate(foreignUser, viewer).andExpect(status().isForbidden());
        reactivate(owner, operator).andExpect(status().isConflict());
        reactivate(owner, owner).andExpect(status().isConflict());
        reactivate(owner, new IdentityId(UUID.randomUUID())).andExpect(status().isNotFound());

        mvc.perform(get("/api/memberships").with(user(owner)).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(jsonPath("$[?(@.identityId == '%s')].status".formatted(viewer.value())).value("REVOKED"))
                .andExpect(jsonPath("$[?(@.identityId == '%s')].status".formatted(owner.value())).value("ACTIVE"))
                .andExpect(jsonPath("$[?(@.identityId == '%s')].role".formatted(owner.value())).value("OWNER"));
    }

    private String invite(IdentityId invitee, String role) throws Exception {
        String body = mvc.perform(post("/api/memberships")
                        .with(user(owner)).with(csrf()).header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identityId\":\"%s\",\"role\":\"%s\"}".formatted(invitee.value(), role)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("INVITED"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }

    private String membershipId(IdentityId identity) {
        return contexts.callWithTenantId(tenant.id(), () -> memberships
                .findByTenantIdAndIdentityId(tenant.id(), identity).orElseThrow().id().value().toString());
    }

    private ResultActions accept(IdentityId identity) throws Exception {
        return mvc.perform(post("/api/tenants/{tenantId}/invitation/acceptance", tenant.id().value())
                .with(user(identity)).with(csrf()));
    }

    private ResultActions reactivate(IdentityId actor, IdentityId target) throws Exception {
        return mvc.perform(post("/api/memberships/{identityId}/reactivation", target.value())
                .with(user(actor)).with(csrf()).header("X-Tenant-Id", tenant.id().value()));
    }

    private void assertCannotEnter(IdentityId identity) throws Exception {
        mvc.perform(get("/api/tenants").with(user(identity)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.tenantId == '%s')]".formatted(tenant.id().value())).isEmpty());
        mvc.perform(get("/api/tenants/{tenantId}", tenant.id().value()).with(user(identity)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/memberships").with(user(identity)).header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isForbidden());
    }

    private void assertMembershipEvent(String type, String membershipId, IdentityId actor, String role)
            throws SQLException {
        try (var connection = TenantSecurityIntegrationFixture.migrationConnection();
             var statement = connection.prepareStatement("""
                     SELECT actor_id, new_role, previous_role, outcome FROM dokene.audit_events
                     WHERE tenant_id = ? AND event_type = ? AND target_id = ?
                     """)) {
            statement.setObject(1, tenant.id().value());
            statement.setString(2, type);
            statement.setObject(3, UUID.fromString(membershipId));
            try (var rs = statement.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("actor_id", UUID.class)).isEqualTo(actor.value());
                assertThat(rs.getString("new_role")).isEqualTo(role);
                assertThat(rs.getString("previous_role")).isNull();
                assertThat(rs.getString("outcome")).isEqualTo("SUCCESS");
                assertThat(rs.next()).isFalse();
            }
        }
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity {
    }
}
