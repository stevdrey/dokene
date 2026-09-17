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

import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.SQLException;
import java.time.Instant;
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

    private Tenant tenant;
    private IdentityId owner;
    private IdentityId operator;
    private IdentityId viewer;
    private IdentityId foreignUser;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenant = seedTenant(tenants, "Membership test " + UUID.randomUUID(), now);
        owner = new IdentityId(UUID.randomUUID());
        operator = new IdentityId(UUID.randomUUID());
        viewer = new IdentityId(UUID.randomUUID());
        foreignUser = new IdentityId(UUID.randomUUID());

        seedMembership(memberships, contexts, tenant.id(), owner, TenantRole.OWNER, now);
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
                .andExpect(jsonPath("$.status").value("ACTIVE"));

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

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity {
    }
}
