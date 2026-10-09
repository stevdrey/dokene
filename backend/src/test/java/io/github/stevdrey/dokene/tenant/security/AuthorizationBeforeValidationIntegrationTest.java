package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.SQLException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Issue #162: authorization is evaluated before request binding/validation, so an unauthorized caller gets 403 and an
 * audited denial even when required headers are missing or the body is malformed. The pool is larger than the shared
 * fixture default because denials are audited in an independent (REQUIRES_NEW) transaction.
 */
@SpringBootTest(properties = "dokene.ai.provider=fake")
class AuthorizationBeforeValidationIntegrationTest {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    private static final String CUSTOMER = "/api/customers/" + UUID.randomUUID();
    private static final UUID CONTACT = UUID.randomUUID();
    private static final String MALFORMED_JSON = "{not-json";

    MockMvc mvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @MockitoSpyBean AuditRecorder auditRecorder;

    private Tenant tenant;
    private final Map<TenantRole, IdentityId> identities = new EnumMap<>(TenantRole.class);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenant = seedTenant(tenants, "Authz before validation " + UUID.randomUUID(), now);
        for (TenantRole role : List.of(TenantRole.VIEWER, TenantRole.OPERATOR)) {
            IdentityId identity = new IdentityId(UUID.randomUUID());
            seedMembership(memberships, contexts, tenant.id(), identity, role, now);
            identities.put(role, identity);
        }
        clearInvocations(auditRecorder);
    }

    record Case(String name, TenantRole role, HttpMethod method, String path, String body,
                TenantPermission permission) {
        @Override public String toString() { return name; }
    }

    static Stream<Arguments> unauthorizedMalformedRequests() {
        return Stream.of(
                new Case("PUT follow-up-policy without If-Match", TenantRole.VIEWER, HttpMethod.PUT,
                        "/api/follow-up-policy", "{\"cadenceDays\":7,\"timeZone\":\"UTC\"}", TenantPermission.TENANT_UPDATE),
                new Case("PUT follow-up-policy invalid time zone", TenantRole.OPERATOR, HttpMethod.PUT,
                        "/api/follow-up-policy", "{\"cadenceDays\":7,\"timeZone\":\"Not/AZone\"}", TenantPermission.TENANT_UPDATE),
                new Case("PUT follow-up-policy malformed JSON", TenantRole.VIEWER, HttpMethod.PUT,
                        "/api/follow-up-policy", MALFORMED_JSON, TenantPermission.TENANT_UPDATE),
                new Case("POST customer malformed JSON", TenantRole.VIEWER, HttpMethod.POST,
                        "/api/customers", MALFORMED_JSON, TenantPermission.CUSTOMER_WRITE),
                new Case("PUT customer without If-Match", TenantRole.VIEWER, HttpMethod.PUT,
                        CUSTOMER, "{\"displayName\":\"x\",\"phones\":[]}", TenantPermission.CUSTOMER_WRITE),
                new Case("DELETE customer without If-Match", TenantRole.OPERATOR, HttpMethod.DELETE,
                        CUSTOMER, null, TenantPermission.CUSTOMER_DELETE),
                new Case("PUT do-not-contact without If-Match", TenantRole.VIEWER, HttpMethod.PUT,
                        CUSTOMER + "/do-not-contact", "{\"enabled\":true,\"source\":\"CUSTOMER_VERBAL\"}",
                        TenantPermission.CUSTOMER_WRITE),
                new Case("PUT consent with unknown channel", TenantRole.VIEWER, HttpMethod.PUT,
                        CUSTOMER + "/contacts/" + CONTACT + "/consents/NOPE", "{}", TenantPermission.CUSTOMER_WRITE),
                new Case("PUT customer follow-up-policy without If-Match", TenantRole.VIEWER, HttpMethod.PUT,
                        CUSTOMER + "/follow-up-policy", "{\"cadenceDays\":7}", TenantPermission.FOLLOWUP_WRITE),
                new Case("POST manual-follow-ups without headers", TenantRole.VIEWER, HttpMethod.POST,
                        CUSTOMER + "/manual-follow-ups", null, TenantPermission.FOLLOWUP_WRITE),
                new Case("POST follow-up-dismissals without headers", TenantRole.VIEWER, HttpMethod.POST,
                        CUSTOMER + "/follow-up-dismissals", null, TenantPermission.FOLLOWUP_WRITE),
                new Case("PUT follow-up-snooze without If-Match", TenantRole.VIEWER, HttpMethod.PUT,
                        CUSTOMER + "/follow-up-snooze", "{}", TenantPermission.FOLLOWUP_WRITE),
                new Case("POST recommendation malformed JSON", TenantRole.VIEWER, HttpMethod.POST,
                        CUSTOMER + "/recommendation", MALFORMED_JSON, TenantPermission.FOLLOWUP_EVALUATE),
                new Case("POST recommendation malformed customer id", TenantRole.VIEWER, HttpMethod.POST,
                        "/api/customers/not-a-uuid/recommendation", "{}", TenantPermission.FOLLOWUP_EVALUATE),
                new Case("POST draft malformed JSON", TenantRole.VIEWER, HttpMethod.POST,
                        CUSTOMER + "/draft", MALFORMED_JSON, TenantPermission.MESSAGE_DRAFT),
                new Case("POST draft malformed customer id", TenantRole.VIEWER, HttpMethod.POST,
                        "/api/customers/not-a-uuid/draft", "{}", TenantPermission.MESSAGE_DRAFT),
                new Case("POST purchase without Idempotency-Key", TenantRole.VIEWER, HttpMethod.POST,
                        CUSTOMER + "/purchases", "{}", TenantPermission.PURCHASE_WRITE),
                new Case("PUT purchase without If-Match", TenantRole.VIEWER, HttpMethod.PUT,
                        CUSTOMER + "/purchases/" + UUID.randomUUID(), "{}", TenantPermission.PURCHASE_WRITE),
                new Case("DELETE purchase without If-Match", TenantRole.VIEWER, HttpMethod.DELETE,
                        CUSTOMER + "/purchases/" + UUID.randomUUID(), null, TenantPermission.PURCHASE_WRITE),
                new Case("POST membership malformed JSON", TenantRole.VIEWER, HttpMethod.POST,
                        "/api/memberships", MALFORMED_JSON, TenantPermission.MEMBERSHIP_INVITE),
                new Case("PUT membership role malformed JSON", TenantRole.VIEWER, HttpMethod.PUT,
                        "/api/memberships/" + UUID.randomUUID() + "/role", MALFORMED_JSON,
                        TenantPermission.MEMBERSHIP_ROLE_UPDATE),
                new Case("DELETE membership", TenantRole.VIEWER, HttpMethod.DELETE,
                        "/api/memberships/" + UUID.randomUUID(), null, TenantPermission.MEMBERSHIP_REVOKE)
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unauthorizedMalformedRequests")
    void unauthorizedMalformedRequestGets403AndAuditedDenial(Case c) throws Exception {
        MockHttpServletRequestBuilder builder = request(c.method(), c.path())
                .with(user(identities.get(c.role()))).with(csrf())
                .header("X-Tenant-Id", tenant.id().value());
        if (c.body() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(c.body());
        }

        mvc.perform(builder).andExpect(status().isForbidden());

        // Exactly one denial, for the first permission the endpoint requires: no duplicates from the service layer.
        verify(auditRecorder, times(1)).authorizationDenied(eq(c.permission()), any());
        verify(auditRecorder, times(1)).authorizationDenied(any(), any());
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
