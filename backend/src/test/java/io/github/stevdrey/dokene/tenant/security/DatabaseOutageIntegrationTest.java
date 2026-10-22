// ABOUTME: Proves issue 246 over the real HTTP stack: with no runtime connection available, data endpoints fail fast
// ABOUTME: with an empty 503 and Retry-After, log no driver text, and the same session works again once the pool frees.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zaxxer.hikari.HikariDataSource;
import io.github.stevdrey.dokene.audit.security.AuditRequestFilter;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(properties = {"dokene.datasource.unavailable-retry-after=7s",
        "dokene.datasource.statement-timeout=300ms",
        "dokene.security.cors.allowed-origins=" + DatabaseOutageIntegrationTest.SPA_ORIGIN})
@ExtendWith(OutputCaptureExtension.class)
class DatabaseOutageIntegrationTest {
    static final String SPA_ORIGIN = "http://localhost:5173";
    private static final String CREATE_BODY = """
            {"displayName":"Outage customer","phones":[{"number":"8888 7777","region":"CR","primary":true}]}
            """;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        // One connection, held by the test, makes the runtime pool unreachable exactly as an outage does for callers:
        // every borrow waits connection-timeout and then fails with SQLTransientConnectionException. The configured
        // 1000 ms validation timeout is then capped at half of this wait (500 ms).
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "1000");
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired AuditRequestFilter auditRequestFilter;
    @Autowired HikariDataSource runtimePool;
    @Autowired DatabaseUnavailability databaseUnavailability;
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
        tenant = seedTenant(tenants, contexts, "Outage tenant " + UUID.randomUUID(), now);
        owner = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), owner, TenantRole.OWNER, now);
    }

    @Test
    void dataEndpointsFailFastWithAnEmptyRetryableUnavailableAndRecoverWithoutRestart(CapturedOutput output)
            throws Exception {
        // Startup logs legitimately name the container host; only what the outage writes is under test.
        int outputBeforeOutage = output.length();
        try (Connection held = runtimePool.getConnection()) {
            assertThat(held.isValid(1)).isTrue();
            assertFailsFastWithRetryableUnavailable(get("/api/customers"));
            assertFailsFastWithRetryableUnavailable(post("/api/customers").with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY));
            assertFailsFastWithRetryableUnavailable(get("/api/follow-up-queue"));
            // A cross-origin SPA must be able to read the 503: the CORS headers survive the response reset.
            assertFailsFastWithRetryableUnavailable(get("/api/customers").header("Origin", SPA_ORIGIN))
                    .andExpect(header().string("Access-Control-Allow-Origin", SPA_ORIGIN))
                    // Retry-After is not CORS-safelisted: without this the SPA could not read the advertised delay.
                    .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")));
        }

        assertThat(output.toString().substring(outputBeforeOutage))
                .contains("Database unavailable at the HTTP boundary")
                .contains("exceptionClass=java.sql.SQLTransientConnectionException")
                // Hikari's message names the pool and the timeout; driver text can name hosts and users.
                .doesNotContain("Connection is not available")
                .doesNotContain(TenantSecurityIntegrationFixture.POSTGRES.getHost() + ":")
                .doesNotContain(TenantSecurityIntegrationFixture.RUNTIME_ROLE + "\"");

        // The database is back: the same caller works again, and the refused write left nothing behind.
        mvc.perform(asOwner(get("/api/customers")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Outage customer"))));
    }

    private ResultActions assertFailsFastWithRetryableUnavailable(MockHttpServletRequestBuilder request)
            throws Exception {
        long started = System.nanoTime();
        ResultActions result = mvc.perform(asOwner(request))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(header().exists(AuditRequestFilter.CORRELATION_HEADER))
                // The BFF security headers survive the filter's response reset (ADR 0023, security invariant 22).
                .andExpect(header().string("Content-Security-Policy",
                        TenantSecurityConfiguration.API_CONTENT_SECURITY_POLICY))
                .andExpect(header().string("Permissions-Policy", TenantSecurityConfiguration.PERMISSIONS_POLICY))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(content().string(""));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        return result;
    }

    @Test
    void inFlightStatementsAndPooledConnectionChecksAreBoundedToo() {
        // pgJDBC disables its socket timeout by default; without it a blackholed network path hangs a statement.
        assertThat(runtimePool.getDataSourceProperties())
                .containsEntry("socketTimeout", "30")
                .containsEntry("options", "-c statement_timeout=300");
        assertThat(runtimePool.getValidationTimeout()).isEqualTo(500);
    }

    @Test
    void aMerelyLongStatementIsCancelledByTheServerAsNoOutageAndKeepsItsConnection() throws Exception {
        try (Connection connection = runtimePool.getConnection(); Statement statement = connection.createStatement()) {
            // The server's statement_timeout fires long before the 30 s socket ceiling, so a long lock wait or query
            // is a cancelled statement (57014), not a lost connection reported to the client as an outage.
            assertThatThrownBy(() -> statement.execute("SELECT pg_sleep(2)"))
                    .isInstanceOfSatisfying(SQLException.class, cancelled -> {
                        assertThat(cancelled.getSQLState()).isEqualTo("57014");
                        assertThat(databaseUnavailability.isCause(cancelled)).isFalse();
                    });
            assertThat(connection.isValid(1)).isTrue();
            assertThat(statement.execute("SELECT 1")).isTrue();
        }
    }

    private MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
        return request.with(user(owner)).header("X-Tenant-Id", tenant.id().value());
    }

    private static RequestPostProcessor user(IdentityId identityId) {
        return authentication(new UsernamePasswordAuthenticationToken(new TestPrincipal(identityId), "test", List.of()));
    }

    private record TestPrincipal(IdentityId identityId) implements AuthenticatedTenantIdentity { }
}
