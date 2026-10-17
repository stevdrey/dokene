package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.migrationConnection;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Issue #207: tenant-selection rejections raised by {@link TenantContextRequestFilter} use the closed problem+json
 * shape on Phase 3 routes (ADR 0023 §4.4) with unchanged statuses, while existing routes keep the empty body.
 */
@SpringBootTest(properties = "dokene.ai.provider=fake")
class TenantSelectionProblemIntegrationTest {
    private static final String PROBLEM = "application/problem+json";
    private static final List<String> PHASE_3_MODULES = List.of(
            "io.github.stevdrey.dokene.messaging.", "io.github.stevdrey.dokene.integration.",
            "io.github.stevdrey.dokene.template.");
    private static final String UUID_SAMPLE = "00000000-0000-4000-8000-000000000000";
    private static final List<String> PATH_SAMPLES = List.of("sample", UUID_SAMPLE, "0");
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
                "POST /api/integrations/whatsapp/ownership-verifications",
                "GET /api/templates",
                "POST /api/templates/GENERAL_FOLLOW_UP/approval-refresh");
    }

    @ParameterizedTest
    @MethodSource("phase3Routes")
    void phase3RoutesAnswerTenantSelectionRejectionsWithTheClosedProblemShape(String route) throws Exception {
        expectAuditedProblem(perform(route, request -> request), 403, FORBIDDEN);
        expectAuditedProblem(perform(route, request -> request
                .header("X-Tenant-Id", memberTenant.id().value())
                .header("X-Tenant-Id", foreignTenant.id().value())), 400, INVALID_INPUT);
        expectAuditedProblem(perform(route, request -> request.header("X-Tenant-Id", "not-a-uuid")), 400, INVALID_INPUT);
        expectAuditedProblem(perform(route, request -> request.header("X-Tenant-Id", foreignTenant.id().value())),
                403, FORBIDDEN);
    }

    /**
     * Every {@code /api} handler of a Phase 3 module is claimed by exactly one responder and no other handler is, so a
     * new Phase 3 route cannot silently fall back to the empty body, an existing endpoint cannot gain one, and the
     * filter's first-match selection never depends on bean order (Issue #222).
     */
    @Test
    void responderOwnershipMatchesThePhase3ControllerRoutesExactly() {
        RouteOwnership ownership = routeOwnership(registeredResponders());
        assertThat(ownership.phase3Handlers()).as("scanned Phase 3 handlers").isGreaterThanOrEqualTo(12);
        assertThat(ownership.unclaimedPhase3()).as("Phase 3 routes without a tenant-selection responder").isEmpty();
        assertThat(ownership.overlappingPhase3())
                .as("Phase 3 routes claimed by more than one tenant-selection responder").isEmpty();
        assertThat(ownership.claimedOther()).as("non-Phase 3 routes claimed by a tenant-selection responder").isEmpty();
    }

    /**
     * The exclusivity check reports a duplicated wildcard, a specific path nested under another owner, an owner
     * limited to the implicit HEAD of a GET route, and an owner limited to UUID-shaped path variables.
     */
    @Test
    void overlappingResponderOwnershipIsReported() {
        Map<String, TenantSelectionRejectionResponder> responders = new TreeMap<>(registeredResponders());
        responders.put("overlappingWildcard", new OverlappingResponder("/api/messages/**"));
        responders.put("overlappingSpecific", new OverlappingResponder("/api/templates"));
        responders.put("overlappingHead",
                new OverlappingResponder(PathPatternRequestMatcher.pathPattern(HttpMethod.HEAD, "/api/templates/**")));
        responders.put("overlappingConstrained",
                new OverlappingResponder("/api/customers/{customerId:[0-9a-f-]{36}}/messages"));

        RouteOwnership ownership = routeOwnership(responders);

        assertThat(ownership.overlappingPhase3())
                .contains("GET /api/messages at /api/messages claimed by "
                        + "[messageRoutesTenantSelectionResponder, overlappingWildcard]")
                .contains("GET /api/templates at /api/templates claimed by "
                        + "[overlappingSpecific, templateTenantSelectionResponder]")
                .contains("HEAD /api/templates at /api/templates claimed by "
                        + "[overlappingHead, overlappingSpecific, templateTenantSelectionResponder]")
                .contains("POST /api/customers/{customerId}/messages at /api/customers/" + UUID_SAMPLE
                        + "/messages claimed by [messageRoutesTenantSelectionResponder, overlappingConstrained]")
                .noneMatch(overlap -> overlap.contains("/api/customers/sample/messages"))
                .allSatisfy(overlap -> assertThat(overlap).containsAnyOf(
                        "overlappingWildcard", "overlappingSpecific", "overlappingHead", "overlappingConstrained"));
        assertThat(ownership.unclaimedPhase3()).isEmpty();
        assertThat(ownership.claimedOther()).isEmpty();
    }

    /** A quantified constraint is parsed as one variable, and a constraint no sample satisfies fails loudly. */
    @Test
    void pathSamplesHonourQuantifiedConstraints() {
        assertThat(samplePaths("/api/messages/{messageId:[0-9a-f-]{36}}/events"))
                .containsExactly("/api/messages/" + UUID_SAMPLE + "/events");
        assertThat(samplePaths("/api/templates/{templateIntent}"))
                .containsExactly("/api/templates/sample", "/api/templates/" + UUID_SAMPLE, "/api/templates/0");
        assertThatThrownBy(() -> samplePaths("/api/messages/{messageId:[x-z]{3}}"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("add one to PATH_SAMPLES");
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

    private static void expectAuditedProblem(ResultActions result, int status, String body) throws Exception {
        result.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(content().json(body, JsonCompareMode.STRICT))
                .andExpect(header().string("Content-Security-Policy",
                        TenantSecurityConfiguration.API_CONTENT_SECURITY_POLICY));
        UUID correlation = UUID.fromString(
                result.andReturn().getResponse().getHeader(AuditRequestFilter.CORRELATION_HEADER));
        try (Connection migration = migrationConnection()) {
            assertThat(queryInt(migration, "SELECT count(*) FROM dokene.audit_events WHERE correlation_id = ? "
                    + "AND event_type = 'AUTHORIZATION_DENIED' AND denial_reason = 'NO_TENANT_CONTEXT'", correlation))
                    .as("audited tenant-selection denial").isEqualTo(1);
        }
    }

    private Map<String, TenantSelectionRejectionResponder> registeredResponders() {
        return webApplicationContext.getBeansOfType(TenantSelectionRejectionResponder.class);
    }

    /**
     * Names the responders claiming each registered {@code /api} handler route, for every method Spring routes to it
     * and several sample values per path variable. Ownership is sampled, not proven over every matcher's domain.
     */
    private RouteOwnership routeOwnership(Map<String, TenantSelectionRejectionResponder> responders) {
        RequestMappingHandlerMapping handlerMappings = webApplicationContext
                .getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        List<String> unclaimedPhase3 = new ArrayList<>();
        List<String> overlappingPhase3 = new ArrayList<>();
        List<String> claimedOther = new ArrayList<>();
        int phase3Handlers = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> handler : handlerMappings.getHandlerMethods().entrySet()) {
            boolean phase3 = isPhase3Module(handler.getValue().getBeanType());
            List<String> methods = effectiveMethods(handler.getKey());
            for (String pattern : handler.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                List<String> paths = samplePaths(pattern);
                for (String method : methods) {
                    if (phase3) {
                        phase3Handlers++;
                    }
                    for (String path : paths) {
                        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
                        List<String> owners = responders.entrySet().stream()
                                .filter(responder -> responder.getValue().handles(request))
                                .map(Map.Entry::getKey).sorted().toList();
                        String route = method + " " + pattern + " at " + path;
                        if (phase3) {
                            if (owners.isEmpty()) {
                                unclaimedPhase3.add(route);
                            } else if (owners.size() > 1) {
                                overlappingPhase3.add(route + " claimed by " + owners);
                            }
                        } else if (!owners.isEmpty()) {
                            claimedOther.add(route + " claimed by " + owners);
                        }
                    }
                }
            }
        }
        return new RouteOwnership(phase3Handlers, unclaimedPhase3, overlappingPhase3, claimedOther);
    }

    /** An unrestricted mapping accepts every method and a GET mapping also answers HEAD. */
    private static List<String> effectiveMethods(RequestMappingInfo mapping) {
        Set<RequestMethod> declared = mapping.getMethodsCondition().getMethods();
        Set<RequestMethod> methods = declared.isEmpty()
                ? EnumSet.allOf(RequestMethod.class) : EnumSet.copyOf(declared);
        if (methods.contains(RequestMethod.GET)) {
            methods.add(RequestMethod.HEAD);
        }
        return methods.stream().map(RequestMethod::name).toList();
    }

    /**
     * Substitutes every path variable with each sample, in all combinations, and keeps the paths the handler's own
     * pattern accepts, so a regex constraint (quantifier braces included) is honoured by Spring's parser itself.
     */
    private static List<String> samplePaths(String pattern) {
        List<String> paths = List.of("");
        int literalStart = 0;
        for (int open = pattern.indexOf('{'); open >= 0; open = pattern.indexOf('{', literalStart)) {
            String literal = pattern.substring(literalStart, open);
            paths = paths.stream().flatMap(path -> PATH_SAMPLES.stream().map(value -> path + literal + value)).toList();
            literalStart = closingBrace(pattern, open) + 1;
        }
        String tail = pattern.substring(literalStart);
        PathPattern handler = PathPatternParser.defaultInstance.parse(pattern);
        List<String> accepted = paths.stream().map(path -> path + tail)
                .filter(path -> handler.matches(PathContainer.parsePath(path))).toList();
        assertThat(accepted).as("path samples accepted by %s; add one to PATH_SAMPLES", pattern).isNotEmpty();
        return accepted;
    }

    /** Finds the brace closing the variable opened at {@code open}, past any braces inside its constraint. */
    private static int closingBrace(String pattern, int open) {
        int depth = 0;
        for (int index = open; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        throw new IllegalArgumentException("Unbalanced path variable in " + pattern);
    }


    private record RouteOwnership(int phase3Handlers, List<String> unclaimedPhase3, List<String> overlappingPhase3,
            List<String> claimedOther) { }

    /** Claims {@code pattern} on top of the registered owners, to prove an overlap cannot pass unnoticed. */
    private static final class OverlappingResponder extends ProblemJsonTenantSelectionResponder {
        private record Echo(TenantSelectionRejection rejection) { }

        OverlappingResponder(String pattern) {
            this(PathPatternRequestMatcher.pathPattern(pattern));
        }

        OverlappingResponder(RequestMatcher ownedRoutes) {
            super(ownedRoutes, Echo::new, echo -> echo.rejection().status());
        }
    }

    private static boolean isPhase3Module(Class<?> controller) {
        return PHASE_3_MODULES.stream().anyMatch(module -> controller.getPackageName().startsWith(module));
    }

    private static void expectEmpty(ResultActions result, int status) throws Exception {
        result.andExpect(status().is(status)).andExpect(content().string(""));
    }

    private static RequestPostProcessor user(IdentityId identityId) {
        return authentication(new UsernamePasswordAuthenticationToken(new TestPrincipal(identityId), "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
