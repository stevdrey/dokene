package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
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
class ContactPolicyEndpointIntegrationTest {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    MockMvc mvc;
    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired ObjectMapper objectMapper;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;

    private Tenant tenant;
    private IdentityId owner;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenant = seedTenant(tenants, "Endpoint tenant " + UUID.randomUUID(), now);
        owner = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), owner, TenantRole.OWNER, now);
    }

    @Test
    void exercisesConsentOverrideHistoryAndAuthorizationThroughTheHttpStack() throws Exception {
        String customerDocument = mvc.perform(post("/api/customers").with(user(owner)).with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"HTTP customer","phones":[
                                  {"number":"8888 7777","region":"CR","primary":true}
                                ]}
                                """))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andReturn().getResponse().getContentAsString();
        var customer = objectMapper.readTree(customerDocument);
        UUID customerId = UUID.fromString(customer.get("id").asText());
        UUID contactId = UUID.fromString(customer.get("phones").get(0).get("id").asText());

        mvc.perform(get("/api/customers/{id}/contact-policy", customerId).with(user(owner))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.consents[0].status").value("UNKNOWN"));

        mvc.perform(put("/api/customers/{customerId}/contacts/{contactId}/consents/WHATSAPP", customerId, contactId)
                        .with(user(owner)).with(csrf()).header("X-Tenant-Id", tenant.id().value())
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"GRANTED\",\"source\":\"CUSTOMER_WRITTEN\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.consents[0].status").value("GRANTED"));
        assertEligibility(customerId, contactId, true, null);

        mvc.perform(put("/api/customers/{id}/do-not-contact", customerId).with(user(owner)).with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()).header("If-Match", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"source\":\"CUSTOMER_VERBAL\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        assertEligibility(customerId, contactId, false, "DO_NOT_CONTACT");

        mvc.perform(put("/api/customers/{id}/do-not-contact", customerId).with(user(owner)).with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()).header("If-Match", "2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false,\"source\":\"OPERATOR_CORRECTION\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""));
        assertEligibility(customerId, contactId, true, null);

        mvc.perform(get("/api/customers/{id}/contact-policy/history", customerId).with(user(owner))
                        .header("X-Tenant-Id", tenant.id().value()).param("limit", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.events[0].actorId").value(owner.value().toString()));

        Tenant foreignTenant = seedTenant(tenants, "Foreign endpoint tenant " + UUID.randomUUID(), Instant.now());
        IdentityId foreignOwner = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, foreignTenant.id(), foreignOwner, TenantRole.OWNER, Instant.now());
        mvc.perform(get("/api/customers/{id}/contact-policy", customerId).with(user(foreignOwner))
                        .header("X-Tenant-Id", foreignTenant.id().value()))
                .andExpect(status().isNotFound());

        IdentityId viewer = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), viewer, TenantRole.VIEWER, Instant.now());
        mvc.perform(put("/api/customers/{id}/do-not-contact", customerId).with(user(viewer)).with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()).header("If-Match", "3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"source\":\"CUSTOMER_VERBAL\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/customers/{id}/contact-policy", customerId)
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changesPolicyEtagAndRejectsStaleMutationAfterReplacingContactIdentity() throws Exception {
        String customerDocument = mvc.perform(post("/api/customers").with(user(owner)).with(csrf())
                        .header("X-Tenant-Id", tenant.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"ETag customer","phones":[
                                  {"number":"8888 7777","region":"CR","primary":true}
                                ]}
                                """))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
                .andReturn().getResponse().getContentAsString();
        UUID customerId = UUID.fromString(objectMapper.readTree(customerDocument).get("id").asText());

        mvc.perform(get("/api/customers/{id}/contact-policy", customerId).with(user(owner))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""));

        mvc.perform(put("/api/customers/{id}", customerId).with(user(owner)).with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName":"ETag customer","phones":[
                                  {"number":"8888 6666","region":"CR","primary":true}
                                ]}
                                """))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));

        mvc.perform(get("/api/customers/{id}/contact-policy", customerId).with(user(owner))
                        .header("X-Tenant-Id", tenant.id().value()))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.consents[0].status").value("UNKNOWN"));

        mvc.perform(put("/api/customers/{id}/do-not-contact", customerId).with(user(owner)).with(csrf())
                        .header("X-Tenant-Id", tenant.id().value()).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true,\"source\":\"CUSTOMER_VERBAL\"}"))
                .andExpect(status().isConflict());
    }

    private void assertEligibility(UUID customerId, UUID contactId, boolean eligible, String reason) throws Exception {
        var result = mvc.perform(get("/api/customers/{id}/contact-eligibility", customerId).with(user(owner))
                        .header("X-Tenant-Id", tenant.id().value())
                        .param("channel", "WHATSAPP").param("contactId", contactId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eligible").value(eligible));
        if (reason == null) result.andExpect(jsonPath("$.reasons").isEmpty());
        else result.andExpect(jsonPath("$.reasons[0]").value(reason));
    }

    private RequestPostProcessor user(IdentityId identityId) {
        var principal = new TestPrincipal(identityId);
        return authentication(new UsernamePasswordAuthenticationToken(principal, "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
