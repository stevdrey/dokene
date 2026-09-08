package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PurchaseCurlSystemTest.CurlSecurityConfiguration.class)
class PurchaseCurlSystemTest {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @LocalServerPort int port;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired ObjectMapper json;

    private UUID tenantId;
    private UUID identityId;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();
        var tenant = seedTenant(tenants, "Curl tenant " + UUID.randomUUID(), now);
        var identity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), identity, TenantRole.OWNER, now);
        tenantId = tenant.id().value();
        identityId = identity.value();
    }

    @Test
    void exercisesHappyPathAndEdgeCasesWithRealCurl() throws Exception {
        CurlResult customer = curl("POST", "/api/customers", null, null, """
                {"displayName":"Curl customer","phones":[{"number":"88887777","region":"CR","primary":true}]}
                """);
        assertThat(customer.status()).isEqualTo(201);
        UUID customerId = UUID.fromString(json.readTree(customer.body()).get("id").asText());
        String base = "/api/customers/" + customerId + "/purchases";

        assertStatus(curl("GET", base + "/last", null, null, null), 204);

        Instant firstTime = Instant.now().minusSeconds(3_600);
        CurlResult created = curl("POST", base, "Idempotency-Key", "curl-first",
                payload(firstTime, "Coffee beans"));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.header("etag")).isEqualTo("\"0\"");
        JsonNode first = json.readTree(created.body());
        UUID firstId = UUID.fromString(first.get("id").asText());

        CurlResult replay = curl("POST", base, "Idempotency-Key", "curl-first",
                payload(firstTime, "Coffee beans"));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).get("id").asText()).isEqualTo(firstId.toString());
        assertStatus(curl("POST", base, "Idempotency-Key", "curl-first",
                payload(firstTime, "Different payload")), 409);

        Instant secondTime = Instant.now().minusSeconds(1_800);
        CurlResult second = curl("POST", base, "Idempotency-Key", "curl-second",
                payload(secondTime, "Tea"));
        UUID secondId = UUID.fromString(json.readTree(second.body()).get("id").asText());
        assertThat(json.readTree(curl("GET", base + "/last", null, null, null).body()).get("id").asText())
                .isEqualTo(secondId.toString());

        Instant correctedTime = Instant.now().minusSeconds(900);
        CurlResult corrected = curl("PUT", base + "/" + firstId, "If-Match", "\"0\"",
                payload(correctedTime, "Corrected coffee"));
        assertThat(corrected.status()).isEqualTo(200);
        assertThat(corrected.header("etag")).isEqualTo("\"1\"");
        assertThat(json.readTree(curl("GET", base + "/last", null, null, null).body()).get("id").asText())
                .isEqualTo(firstId.toString());
        assertStatus(curl("PUT", base + "/" + firstId, "If-Match", "\"0\"",
                payload(correctedTime, "Stale")), 409);

        assertStatus(curl("DELETE", base + "/" + firstId, "If-Match", "\"1\"", null), 204);
        assertThat(json.readTree(curl("GET", base + "/last", null, null, null).body()).get("id").asText())
                .isEqualTo(secondId.toString());
        JsonNode history = json.readTree(curl("GET", base + "/" + firstId + "/history?limit=2", null, null, null).body());
        assertThat(history.get("events").size()).isEqualTo(2);
        assertThat(history.get("nextCursor").asText()).isNotBlank();

        JsonNode page = json.readTree(curl("GET", base + "?limit=1", null, null, null).body());
        assertThat(page.get("purchases").size()).isEqualTo(1);
        assertThat(page.get("nextCursor").asText()).isNotBlank();
        assertThat(json.readTree(curl("GET", base + "?status=VOID", null, null, null).body())
                .get("purchases").get(0).get("id").asText()).isEqualTo(firstId.toString());

        assertStatus(curl("POST", base, null, null, payload(firstTime, "No key")), 400);
        assertStatus(curl("POST", base, "Idempotency-Key", "spaces are invalid", payload(firstTime, "Bad key")), 400);
        assertStatus(curl("POST", base, "Idempotency-Key", "future", payload(Instant.now().plusSeconds(600), "Future")), 400);
        assertStatus(curl("POST", base, "Idempotency-Key", "blank", payload(firstTime, "   ")), 400);
        assertStatus(curl("POST", base, "Idempotency-Key", "missing-field", "{\"description\":\"Missing time\"}"), 400);
        assertStatus(curl("POST", base, "Idempotency-Key", "malformed", "{not-json}"), 400);
        assertStatus(curl("GET", base + "?limit=0", null, null, null), 400);
        assertStatus(curl("GET", base + "?limit=101", null, null, null), 400);
        assertStatus(curl("GET", base + "?status=INVALID", null, null, null), 400);
        assertStatus(curl("GET", base + "?cursor=not-base64", null, null, null), 400);
        assertStatus(curl("PUT", base + "/" + secondId, null, null, payload(secondTime, "No version")), 400);
        assertStatus(curl("GET", base + "/" + UUID.randomUUID(), null, null, null), 404);

        CurlResult unauthenticated = curlRaw("GET", base, List.of("X-Tenant-Id: " + tenantId), null);
        assertThat(unauthenticated.status()).isEqualTo(403);
        CurlResult missingTenant = curlRaw("GET", base, List.of("X-Test-Identity: " + identityId), null);
        assertThat(missingTenant.status()).isEqualTo(403);
        CurlResult foreignTenant = curlRaw("GET", base, List.of("X-Test-Identity: " + identityId,
                "X-Tenant-Id: " + UUID.randomUUID()), null);
        assertThat(foreignTenant.status()).isEqualTo(403);
    }

    private CurlResult curl(String method, String path, String extraName, String extraValue, String body) throws Exception {
        List<String> headers = new ArrayList<>(List.of("X-Test-Identity: " + identityId, "X-Tenant-Id: " + tenantId));
        if (extraName != null) headers.add(extraName + ": " + extraValue);
        return curlRaw(method, path, headers, body);
    }

    private CurlResult curlRaw(String method, String path, List<String> headers, String body) throws Exception {
        Path headerFile = Files.createTempFile("dokene-curl-headers-", ".txt");
        Path bodyFile = Files.createTempFile("dokene-curl-body-", ".json");
        try {
            List<String> command = new ArrayList<>(List.of("curl", "--silent", "--show-error", "--request", method,
                    "--dump-header", headerFile.toString(), "--output", bodyFile.toString(),
                    "--write-out", "%{http_code}", "http://127.0.0.1:" + port + path));
            for (String header : headers) command.addAll(List.of("--header", header));
            if (body != null) command.addAll(List.of("--header", "Content-Type: application/json", "--data", body));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
                throw new AssertionError("curl timed out after 30 seconds");
            }
            String statusText = new String(process.getInputStream().readAllBytes()).strip();
            assertThat(process.exitValue()).withFailMessage("curl failed: %s", statusText).isZero();
            return new CurlResult(Integer.parseInt(statusText), Files.readString(headerFile), Files.readString(bodyFile));
        } finally {
            Files.deleteIfExists(headerFile);
            Files.deleteIfExists(bodyFile);
        }
    }

    private String payload(Instant at, String description) throws Exception {
        var root = json.createObjectNode();
        root.put("purchasedAt", at.toString());
        root.put("description", description);
        return json.writeValueAsString(root);
    }
    private static void assertStatus(CurlResult result, int expected) { assertThat(result.status()).isEqualTo(expected); }

    record CurlResult(int status, String headers, String body) {
        String header(String name) {
            return headers.lines().filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip()).findFirst().orElse(null);
        }
    }

    @TestConfiguration
    static class CurlSecurityConfiguration {
        @Bean
        @Order(0)
        SecurityFilterChain curlSecurity(HttpSecurity http, TenantContextRequestFilter tenantFilter) throws Exception {
            return http.securityMatcher("/**")
                    .csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .anyRequest().authenticated())
                    .addFilterBefore(new HeaderIdentityFilter(), AnonymousAuthenticationFilter.class)
                    .addFilterAfter(tenantFilter, AnonymousAuthenticationFilter.class)
                    .build();
        }
    }

    static class HeaderIdentityFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String value = request.getHeader("X-Test-Identity");
            if (value != null) {
                try {
                    AuthenticatedTenantIdentity principal = () -> new IdentityId(UUID.fromString(value));
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                } catch (IllegalArgumentException ignored) {
                    // Invalid test authentication remains unauthenticated.
                }
            }
            chain.doFilter(request, response);
        }
    }
}
