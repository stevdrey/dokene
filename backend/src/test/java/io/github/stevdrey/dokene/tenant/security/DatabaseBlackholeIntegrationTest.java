// ABOUTME: Proves issue 246 for a blackholed database (no TCP reset): a stale idle pooled connection still fails within
// ABOUTME: the pool wait. It pauses a PostgreSQL of its own, so no other integration class sees the disruption.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "dokene.datasource.unavailable-retry-after=7s")
@DirtiesContext
class DatabaseBlackholeIntegrationTest {
    private static PostgreSQLContainer database;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        database = TenantSecurityIntegrationFixture.startDedicatedWithDatabaseRoles();
        TenantSecurityIntegrationFixture.configure(registry, database);
        // One idle pooled connection and a 1000 ms pool wait, so validation is capped at 500 ms.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    }

    @AfterAll
    static void stopDatabase() {
        if (database != null) {
            database.stop();
        }
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;

    private MockMvc mvc;
    private Tenant tenant;
    private IdentityId owner;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(auditRequestFilter).apply(springSecurity()).build();
        Instant now = Instant.now();
        tenant = seedTenant(tenants, contexts, "Blackhole tenant " + UUID.randomUUID(), now);
        owner = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), owner, TenantRole.OWNER, now);
    }

    @Test
    void aStaleIdleConnectionAfterABlackholedOutageStillFailsWithinThePoolWait() throws Exception {
        var docker = database.getDockerClient();
        // Past Hikari's 500 ms alive-bypass window, so the next borrow validates the idle pooled connection.
        Thread.sleep(700);
        // A paused container accepts nothing and answers nothing, like a blackholed route: no TCP reset arrives.
        docker.pauseContainerCmd(database.getContainerId()).exec();
        try {
            long started = System.nanoTime();
            mvc.perform(asOwner(get("/api/customers")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "7"))
                    .andExpect(content().string(""));
            // Validation (500 ms) plus the rest of the pool wait, never the 30 s socket ceiling.
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_500));
        } finally {
            docker.unpauseContainerCmd(database.getContainerId()).exec();
        }
        awaitRecovery();
    }

    private void awaitRecovery() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int status;
        do {
            status = mvc.perform(asOwner(get("/api/customers"))).andReturn().getResponse().getStatus();
        } while (status != 200 && System.nanoTime() < deadline);
        assertThat(status).isEqualTo(200);
    }

    private MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
        return request.with(user(owner)).header("X-Tenant-Id", tenant.id().value());
    }

    private static RequestPostProcessor user(IdentityId identityId) {
        return authentication(new UsernamePasswordAuthenticationToken(new TestPrincipal(identityId), "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
