package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PurchaseCurlSystemTest.CurlSecurityConfiguration.class)
class FollowUpCurlSystemTest {
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
    private UUID viewerIdentityId;
    private UUID foreignTenantId;
    private UUID foreignIdentityId;

    @BeforeEach
    void seed() {
        Instant now = Instant.now();
        var tenant = seedTenant(tenants, "Follow-up curl " + UUID.randomUUID(), now);
        var identity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), identity, TenantRole.OWNER, now);
        tenantId = tenant.id().value();
        identityId = identity.value();
        var viewer = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), viewer, TenantRole.VIEWER, now);
        viewerIdentityId = viewer.value();
        var foreignTenant = seedTenant(tenants, "Foreign follow-up curl " + UUID.randomUUID(), now);
        var foreignIdentity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, foreignTenant.id(), foreignIdentity, TenantRole.OWNER, now);
        foreignTenantId = foreignTenant.id().value();
        foreignIdentityId = foreignIdentity.value();
    }

    @Test
    void exercisesFollowUpEndpointsWithRealCurl() throws Exception {
        CurlResult customer = curl("POST", "/api/customers", """
                {"displayName":"Curl follow-up customer","phones":[{"number":"88887777","region":"CR","primary":true}]}
                """);
        assertStatus(customer, 201);
        JsonNode customerBody = json.readTree(customer.body());
        UUID customerId = UUID.fromString(customerBody.get("id").asText());
        UUID contactId = UUID.fromString(customerBody.get("phones").get(0).get("id").asText());
        String base = "/api/customers/" + customerId;

        CurlResult defaultPolicyResult = curl("GET", "/api/follow-up-policy", null);
        JsonNode defaultPolicy = json.readTree(defaultPolicyResult.body());
        assertThat(defaultPolicy.get("cadenceDays").asInt()).isEqualTo(30);
        assertThat(defaultPolicy.get("timeZone").asText()).isEqualTo("UTC");
        CurlResult tenantUpdated = curlWithHeader("PUT", "/api/follow-up-policy", "If-Match",
                defaultPolicyResult.header("etag"),
                "{\"cadenceDays\":14,\"timeZone\":\"America/Costa_Rica\"}");
        assertStatus(tenantUpdated, 200);
        assertThat(tenantUpdated.header("etag")).isEqualTo("\"1\"");
        assertStatus(curl("PUT", "/api/follow-up-policy", "{\"cadenceDays\":14,\"timeZone\":\"UTC\"}"), 400);
        assertStatus(curlWithHeader("PUT", "/api/follow-up-policy", "If-Match", "\"0\"",
                "{\"cadenceDays\":14,\"timeZone\":\"UTC\"}"), 409);
        assertStatus(curlWithHeader("PUT", "/api/follow-up-policy", "If-Match", "bad",
                "{\"cadenceDays\":14,\"timeZone\":\"UTC\"}"), 400);
        assertStatus(curlWithHeader("PUT", "/api/follow-up-policy", "If-Match", "\"01\"",
                "{\"cadenceDays\":14,\"timeZone\":\"UTC\"}"), 400);
        assertStatus(curlWithHeader("PUT", "/api/follow-up-policy", "If-Match", "\"1\"",
                "{\"cadenceDays\":0,\"timeZone\":\"UTC\"}"), 400);
        assertStatus(curlWithHeader("PUT", "/api/follow-up-policy", "If-Match", "\"1\"",
                "{\"cadenceDays\":30,\"timeZone\":\"Not/AZone\"}"), 400);
        assertStatus(curlWithHeader("PUT", "/api/follow-up-policy", "If-Match", "\"1\"",
                "{\"cadenceDays\":30,\"timeZone\":\"+02:00\"}"), 400);
        assertStatus(curlWithHeader("PUT", "/api/follow-up-policy", "If-Match", "\"1\"",
                "{\"cadenceDays\":30,\"timeZone\":\"GMT+02:00\"}"), 400);

        assertThat(json.readTree(curl("GET", base + "/follow-up-eligibility", null).body())
                .get("reasons").toString()).contains("NO_ELIGIBLE_CONTACT");
        CurlResult contactPolicy = curl("GET", base + "/contact-policy", null);
        assertStatus(curlWithHeader("PUT", base + "/contacts/" + contactId + "/consents/WHATSAPP",
                "If-Match", contactPolicy.header("etag"),
                "{\"status\":\"GRANTED\",\"source\":\"CUSTOMER_WRITTEN\"}"), 200);

        LocalDate today = LocalDate.now(ZoneId.of("America/Costa_Rica"));
        CurlResult customerPolicy = curl("GET", base + "/follow-up-policy", null);
        assertStatus(curlWithHeader("PUT", base + "/follow-up-policy", "If-Match", "\"00\"",
                "{\"cadenceDays\":7,\"explicitNextDate\":\"" + today + "\"}"), 400);
        assertStatus(curlWithHeader("PUT", base + "/follow-up-policy", "If-Match",
                customerPolicy.header("etag"), "{\"cadenceDays\":0}"), 400);
        assertStatus(curlWithHeader("PUT", base + "/follow-up-policy", "If-Match",
                customerPolicy.header("etag"), "{\"cadenceDays\":3651}"), 400);
        assertStatus(curlWithHeader("PUT", base + "/follow-up-policy", "If-Match",
                customerPolicy.header("etag"), "{\"cadenceDays\":-5}"), 400);
        CurlResult customerUpdated = curlWithHeader("PUT", base + "/follow-up-policy", "If-Match",
                customerPolicy.header("etag"),
                "{\"cadenceDays\":7,\"explicitNextDate\":\"" + today + "\"}");
        assertStatus(customerUpdated, 200);
        JsonNode due = json.readTree(curl("GET", base + "/follow-up-eligibility", null).body());
        assertThat(due.get("status").asText()).isEqualTo("DUE");
        assertThat(due.get("eligible").asBoolean()).isTrue();

        LocalDate tomorrow = today.plusDays(1);
        assertStatus(curlWithHeader("PUT", base + "/follow-up-snooze", "If-Match", "\"01\"",
                "{\"until\":\"" + tomorrow + "\"}"), 400);
        CurlResult snooze = curlWithHeader("PUT", base + "/follow-up-snooze", "If-Match",
                customerUpdated.header("etag"), "{\"until\":\"" + tomorrow + "\"}");
        assertStatus(snooze, 200);
        JsonNode snoozed = json.readTree(curl("GET", base + "/follow-up-eligibility", null).body());
        assertThat(snoozed.get("status").asText()).isEqualTo("NOT_YET_DUE");
        assertThat(snoozed.get("reasons").toString()).contains("SNOOZED");

        assertStatus(curl("POST", base + "/manual-follow-ups", null), 400);
        assertStatus(curlRaw("POST", base + "/manual-follow-ups", List.of(
                "X-Test-Identity: " + identityId, "X-Tenant-Id: " + tenantId,
                "If-Match: \"02\"", "Idempotency-Key: curl-manual-invalid-etag"), null), 400);
        CurlResult manualResult = curlRaw("POST", base + "/manual-follow-ups", List.of(
                "X-Test-Identity: " + identityId, "X-Tenant-Id: " + tenantId,
                "If-Match: " + snooze.header("etag"), "Idempotency-Key: curl-manual-1"), null);
        assertStatus(manualResult, 201);
        JsonNode manual = json.readTree(manualResult.body());
        assertThat(manual.get("completedOn").asText()).isEqualTo(today.toString());
        CurlResult replay = curlRaw("POST", base + "/manual-follow-ups", List.of(
                "X-Test-Identity: " + identityId, "X-Tenant-Id: " + tenantId,
                "If-Match: \"0\"", "Idempotency-Key: curl-manual-1"), null);
        assertStatus(replay, 200);
        assertThat(json.readTree(replay.body()).get("id")).isEqualTo(manual.get("id"));
        assertStatus(curlRaw("POST", base + "/manual-follow-ups", List.of(
                "X-Test-Identity: " + identityId, "X-Tenant-Id: " + tenantId,
                "If-Match: \"3\"", "Idempotency-Key: invalid key"), null), 400);

        assertStatus(curlWithHeader("PUT", base + "/follow-up-snooze", "If-Match", "\"3\"",
                "{\"until\":\"" + today.minusDays(1) + "\"}"), 400);
        assertStatus(curl("PUT", base + "/follow-up-snooze", "{\"until\":\"not-a-date\"}"), 400);
        assertStatus(curl("PUT", base + "/follow-up-policy", "{\"cadenceDays\":3651}"), 400);
        assertStatus(curl("GET", "/api/customers/" + UUID.randomUUID() + "/follow-up-policy", null), 404);

        assertStatus(curlAs("GET", base + "/follow-up-policy", viewerIdentityId, tenantId, null), 200);
        assertStatus(curlAs("GET", base + "/follow-up-eligibility", viewerIdentityId, tenantId, null), 403);
        assertStatus(curlRaw("PUT", "/api/follow-up-policy", List.of("X-Test-Identity: " + viewerIdentityId,
                "X-Tenant-Id: " + tenantId, "If-Match: \"1\""),
                "{\"cadenceDays\":20,\"timeZone\":\"UTC\"}"), 403);
        assertStatus(curlAs("GET", base + "/follow-up-eligibility", foreignIdentityId, foreignTenantId, null), 404);

        assertStatus(curlRaw("GET", base + "/follow-up-eligibility",
                List.of("X-Tenant-Id: " + tenantId), null), 403);
        assertStatus(curlRaw("GET", base + "/follow-up-eligibility",
                List.of("X-Test-Identity: " + identityId), null), 403);
        assertStatus(curlRaw("GET", base + "/follow-up-eligibility",
                List.of("X-Test-Identity: " + identityId, "X-Tenant-Id: " + UUID.randomUUID()), null), 403);
    }

    private CurlResult curl(String method, String path, String body) throws Exception {
        return curlRaw(method, path,
                List.of("X-Test-Identity: " + identityId, "X-Tenant-Id: " + tenantId), body);
    }

    private CurlResult curlAs(String method, String path, UUID identity, UUID tenant, String body) throws Exception {
        return curlRaw(method, path,
                List.of("X-Test-Identity: " + identity, "X-Tenant-Id: " + tenant), body);
    }

    private CurlResult curlWithHeader(String method, String path, String name, String value, String body)
            throws Exception {
        return curlRaw(method, path, List.of("X-Test-Identity: " + identityId,
                "X-Tenant-Id: " + tenantId, name + ": " + value), body);
    }

    private CurlResult curlRaw(String method, String path, List<String> headers, String body) throws Exception {
        Path headerFile = Files.createTempFile("dokene-follow-up-curl-headers-", ".txt");
        Path bodyFile = Files.createTempFile("dokene-follow-up-curl-body-", ".json");
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
            return new CurlResult(Integer.parseInt(statusText), Files.readString(headerFile),
                    Files.readString(bodyFile));
        } finally {
            Files.deleteIfExists(headerFile);
            Files.deleteIfExists(bodyFile);
        }
    }

    private void assertStatus(CurlResult result, int expected) {
        assertThat(result.status()).isEqualTo(expected);
    }

    record CurlResult(int status, String headers, String body) {
        String header(String name) {
            return headers.lines().filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip()).findFirst().orElse(null);
        }
    }
}
