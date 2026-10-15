package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Issue #207: tenant-selection rejections raised by {@link TenantContextRequestFilter} use the closed problem+json
 * shape on Phase 3 routes (ADR 0023 §4.4) with unchanged statuses, while existing routes keep the empty body.
 */
@SpringBootTest(properties = "dokene.ai.provider=fake")
class TenantSelectionProblemIntegrationTest {
    private static final String PROBLEM = "application/problem+json";
    private static final String INVALID_INPUT =
            "{\"type\":\"about:blank\",\"title\":\"Bad Request\",\"status\":400,\"code\":\"INVALID_INPUT\"}";
    private static final String FORBIDDEN =
            "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\"FORBIDDEN\"}";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    MockMvc mvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;

    private Tenant memberTenant;
    private Tenant foreignTenant;
    private IdentityId owner;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        memberTenant = seedTenant(tenants, contexts, "Selection A " + UUID.randomUUID(), now);
        foreignTenant = seedTenant(tenants, contexts, "Selection B " + UUID.randomUUID(), now);
        owner = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, memberTenant.id(), owner, TenantRole.OWNER, now);
    }

    static Stream<String> phase3Routes() {
        return Stream.of(
                "GET /api/messages",
                "GET /api/messages/" + UUID.randomUUID(),
                "POST /api/messages/" + UUID.randomUUID() + "/approvals",
                "POST /api/customers/" + UUID.randomUUID() + "/messages",
                "GET /api/integrations/whatsapp",
                "POST /api/integrations/whatsapp/credential-rotations",
                "GET /api/templates",
                "POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh");
    }

    @ParameterizedTest
    @MethodSource("phase3Routes")
    void phase3RoutesAnswerTenantSelectionRejectionsWithTheClosedProblemShape(String route) throws Exception {
        expectProblem(perform(route, request -> request), 403, FORBIDDEN);
        expectProblem(perform(route, request -> request
                .header("X-Tenant-Id", memberTenant.id().value())
                .header("X-Tenant-Id", foreignTenant.id().value())), 400, INVALID_INPUT);
        expectProblem(perform(route, request -> request.header("X-Tenant-Id", "not-a-uuid")), 400, INVALID_INPUT);
        expectProblem(perform(route, request -> request.header("X-Tenant-Id", foreignTenant.id().value())),
                403, FORBIDDEN);
    }

    @Test
    void existingRoutesKeepTheEmptyBodyAndTheirStatuses() throws Exception {
        String route = "GET /api/customers";
        expectEmpty(perform(route, request -> request), 403);
        expectEmpty(perform(route, request -> request
                .header("X-Tenant-Id", memberTenant.id().value())
                .header("X-Tenant-Id", foreignTenant.id().value())), 400);
        expectEmpty(perform(route, request -> request.header("X-Tenant-Id", "not-a-uuid")), 400);
        expectEmpty(perform(route, request -> request.header("X-Tenant-Id", foreignTenant.id().value())), 403);
    }

    @Test
    void lookalikeRoutesOutsideThePhase3TreesAreNotClaimed() throws Exception {
        expectEmpty(perform("GET /api/messages-archive", request -> request), 403);
        expectEmpty(perform("GET /api/customers/" + UUID.randomUUID() + "/messages-export", request -> request), 403);
    }

    private ResultActions perform(String route, Function<MockHttpServletRequestBuilder, MockHttpServletRequestBuilder>
            selection) throws Exception {
        String[] parts = route.split(" ", 2);
        MockHttpServletRequestBuilder request = "POST".equals(parts[0])
                ? post(parts[1]).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")
                : get(parts[1]);
        return mvc.perform(selection.apply(request.with(user(owner))));
    }

    private static void expectProblem(ResultActions result, int status, String body) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(content().json(body, JsonCompareMode.STRICT))
                .andExpect(header().string("Content-Security-Policy",
                        TenantSecurityConfiguration.API_CONTENT_SECURITY_POLICY));
    }

    private static void expectEmpty(ResultActions result, int status) throws Exception {
        result.andExpect(status().is(status)).andExpect(content().string(""));
    }

    private static RequestPostProcessor user(IdentityId identityId) {
        return authentication(new UsernamePasswordAuthenticationToken(new TestPrincipal(identityId), "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
