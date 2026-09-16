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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end operator journey system test verifying Phase 1 requirements (Issue #39):
 * 1. An operator configures tenant cadence and time zone.
 * 2. Creates a customer and grants WhatsApp consent.
 * 3. Records customer purchase context.
 * 4. Makes customer due according to cadence / explicit date.
 * 5. Queries the due follow-up queue and verifies reason, timing, and consented phone.
 * 6. Exercises snooze disposition, verifying customer drops from queue.
 * 7. Exercises dismissal disposition with notes and idempotency replay.
 * 8. Exercises manual follow-up completion with notes and idempotency replay.
 * 9. Verifies stale candidate safeguards (revoking consent and archiving customer reject dispositions with 409 Conflict).
 * 10. Verifies cross-tenant isolation and VIEWER role boundaries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PurchaseCurlSystemTest.CurlSecurityConfiguration.class)
class FollowUpOperatorJourneySystemTest {

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
    private UUID operatorId;
    private UUID viewerId;
    private UUID foreignTenantId;
    private UUID foreignOperatorId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();

        // Primary workspace: Café & Taller Artesano
        var tenant = seedTenant(tenants, "Operator Journey Tenant " + UUID.randomUUID(), now);
        tenantId = tenant.id().value();

        var operatorIdentity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), operatorIdentity, TenantRole.OWNER, now);
        operatorId = operatorIdentity.value();

        var viewerIdentity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), viewerIdentity, TenantRole.VIEWER, now);
        viewerId = viewerIdentity.value();

        // Foreign workspace (for cross-tenant isolation)
        var foreignTenant = seedTenant(tenants, "Foreign Tenant " + UUID.randomUUID(), now);
        foreignTenantId = foreignTenant.id().value();

        var foreignIdentity = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, foreignTenant.id(), foreignIdentity, TenantRole.OWNER, now);
        foreignOperatorId = foreignIdentity.value();
    }

    @Test
    @DisplayName("Complete operator journey: customer creation, consent, purchase, due queue, dispositions, stale safeguards, and tenant isolation")
    void completeOperatorJourney() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("America/Santiago"));

        // Step 1: Configure Tenant Policy (14 days cadence, America/Santiago)
        CurlResult initialTenantPolicy = curl("GET", "/api/follow-up-policy", null);
        assertStatus(initialTenantPolicy, 200);
        CurlResult updateTenantPolicy = curlWithHeader(
                "PUT",
                "/api/follow-up-policy",
                "If-Match",
                initialTenantPolicy.header("etag"),
                "{\"cadenceDays\":14,\"timeZone\":\"America/Santiago\"}"
        );
        assertStatus(updateTenantPolicy, 200);
        assertThat(updateTenantPolicy.header("etag")).isNotNull();

        // Step 2: Create Customer with primary phone
        CurlResult customerResult = curl("POST", "/api/customers", """
                {
                  "displayName": "Valentina Morales Gómez",
                  "phones": [{"number": "984521190", "region": "CL", "primary": true}]
                }
                """);
        assertStatus(customerResult, 201);
        JsonNode customerNode = json.readTree(customerResult.body());
        UUID customerId = UUID.fromString(customerNode.get("id").asText());
        UUID contactId = UUID.fromString(customerNode.get("phones").get(0).get("id").asText());
        String customerBase = "/api/customers/" + customerId;

        // Step 3: Record WhatsApp Consent
        CurlResult contactPolicy = curl("GET", customerBase + "/contact-policy", null);
        assertStatus(contactPolicy, 200);
        CurlResult consentResult = curlWithHeader(
                "PUT",
                customerBase + "/contacts/" + contactId + "/consents/WHATSAPP",
                "If-Match",
                contactPolicy.header("etag"),
                "{\"status\":\"GRANTED\",\"source\":\"CUSTOMER_WRITTEN\"}"
        );
        assertStatus(consentResult, 200);

        // Step 4: Record Initial Purchase (must be in the past)
        Instant purchaseTime = Instant.now().minusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        CurlResult purchaseResult = curlRaw(
                "POST",
                customerBase + "/purchases",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "Idempotency-Key: op-purchase-1"
                ),
                "{\"purchasedAt\":\"" + purchaseTime + "\",\"description\":\"Kit Harinas Especiales + Esencias\"}"
        );
        assertStatus(purchaseResult, 201);

        // Step 5: Make customer DUE today via explicit next date for operator review
        CurlResult policyBeforeDue = curl("GET", customerBase + "/follow-up-policy", null);
        assertStatus(policyBeforeDue, 200);
        CurlResult setDueResult = curlWithHeader(
                "PUT",
                customerBase + "/follow-up-policy",
                "If-Match",
                policyBeforeDue.header("etag"),
                "{\"cadenceDays\":14,\"explicitNextDate\":\"" + today + "\"}"
        );
        assertStatus(setDueResult, 200);

        // Step 6: Query Due Queue - customer appears with accurate explanation and consented phone
        CurlResult queueResult = curl("GET", "/api/follow-up-queue", null);
        assertStatus(queueResult, 200);
        JsonNode queueNode = json.readTree(queueResult.body());
        assertThat(queueNode.get("items")).isNotEmpty();
        JsonNode queueItem = queueNode.get("items").get(0);
        assertThat(queueItem.get("customerId").asText()).isEqualTo(customerId.toString());
        assertThat(queueItem.get("displayName").asText()).isEqualTo("Valentina Morales Gómez");
        assertThat(queueItem.get("primaryPhone").asText()).isEqualTo("+56984521190");
        assertThat(queueItem.get("status").asText()).isEqualTo("DUE");
        assertThat(queueItem.get("reasons").toString()).contains("DUE_TODAY");

        // Step 7: Dismissal disposition on DUE customer with notes and idempotency
        CurlResult dismissResult = curlRaw(
                "POST",
                customerBase + "/follow-up-dismissals",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: " + setDueResult.header("etag"),
                        "Idempotency-Key: op-dismiss-key-1"
                ),
                "{\"notes\":\"Cliente contactó por canal alternativo\"}"
        );
        assertStatus(dismissResult, 201);
        JsonNode dismissNode = json.readTree(dismissResult.body());
        assertThat(dismissNode.get("dismissedOn").asText()).isEqualTo(today.toString());
        assertThat(dismissNode.get("notes").asText()).isEqualTo("Cliente contactó por canal alternativo");

        // Dismissal advances cadence (today + 14 days), customer drops from queue
        CurlResult queueAfterDismiss = curl("GET", "/api/follow-up-queue", null);
        assertThat(json.readTree(queueAfterDismiss.body()).get("items")).isEmpty();

        // Idempotent dismissal replay with same key returns 200 OK
        CurlResult dismissReplay = curlRaw(
                "POST",
                customerBase + "/follow-up-dismissals",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: \"0\"",
                        "Idempotency-Key: op-dismiss-key-1"
                ),
                "{\"notes\":\"Different notes are ignored on replay\"}"
        );
        assertStatus(dismissReplay, 200);
        assertThat(json.readTree(dismissReplay.body()).get("id").asText())
                .isEqualTo(dismissNode.get("id").asText());

        // Step 8: Dispositions on NOT_YET_DUE customer fail with 409 Conflict
        CurlResult policyAfterDismiss = curl("GET", customerBase + "/follow-up-policy", null);
        assertStatus(curlRaw(
                "POST",
                customerBase + "/follow-up-dismissals",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: " + policyAfterDismiss.header("etag"),
                        "Idempotency-Key: op-dismiss-key-2"
                ),
                null
        ), 409);
        LocalDate tomorrow = today.plusDays(1);
        assertStatus(curlWithHeader(
                "PUT",
                customerBase + "/follow-up-snooze",
                "If-Match",
                policyAfterDismiss.header("etag"),
                "{\"until\":\"" + tomorrow + "\"}"
        ), 409);

        // Step 9: Reset to DUE today to test snooze disposition
        CurlResult resetDueForSnooze = curlWithHeader(
                "PUT",
                customerBase + "/follow-up-policy",
                "If-Match",
                policyAfterDismiss.header("etag"),
                "{\"cadenceDays\":14,\"explicitNextDate\":\"" + today + "\"}"
        );
        assertStatus(resetDueForSnooze, 200);

        // Verify customer returned to queue
        CurlResult queueAfterReset = curl("GET", "/api/follow-up-queue", null);
        assertThat(json.readTree(queueAfterReset.body()).get("items")).isNotEmpty();

        // Step 10: Snooze disposition - customer drops from due queue
        CurlResult snoozeResult = curlWithHeader(
                "PUT",
                customerBase + "/follow-up-snooze",
                "If-Match",
                resetDueForSnooze.header("etag"),
                "{\"until\":\"" + tomorrow + "\"}"
        );
        assertStatus(snoozeResult, 200);

        // Queue is now empty because customer is snoozed until tomorrow
        CurlResult queueAfterSnooze = curl("GET", "/api/follow-up-queue", null);
        assertThat(json.readTree(queueAfterSnooze.body()).get("items")).isEmpty();

        // Step 11: Manual follow-up completion (permitted while snoozed, clears snooze)
        CurlResult policyAfterSnooze = curl("GET", customerBase + "/follow-up-policy", null);
        CurlResult manualResult = curlRaw(
                "POST",
                customerBase + "/manual-follow-ups",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: " + policyAfterSnooze.header("etag"),
                        "Idempotency-Key: op-manual-key-1"
                ),
                "{\"notes\":\"Conversamos por WhatsApp; pedirá la próxima semana\"}"
        );
        assertStatus(manualResult, 201);
        JsonNode manualNode = json.readTree(manualResult.body());
        assertThat(manualNode.get("completedOn").asText()).isEqualTo(today.toString());
        assertThat(manualNode.get("notes").asText()).isEqualTo("Conversamos por WhatsApp; pedirá la próxima semana");

        // Queue is now empty after manual follow-up
        CurlResult queueAfterManual = curl("GET", "/api/follow-up-queue", null);
        assertThat(json.readTree(queueAfterManual.body()).get("items")).isEmpty();

        // Idempotent replay of manual follow-up returns 200 OK
        CurlResult manualReplay = curlRaw(
                "POST",
                customerBase + "/manual-follow-ups",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: \"0\"",
                        "Idempotency-Key: op-manual-key-1"
                ),
                null
        );
        assertStatus(manualReplay, 200);
        assertThat(json.readTree(manualReplay.body()).get("id").asText())
                .isEqualTo(manualNode.get("id").asText());

        // Step 12: Stale Safeguard - Revoking consent prevents acting on customer (409 Conflict)
        CurlResult latestContactPolicy = curl("GET", customerBase + "/contact-policy", null);
        assertStatus(curlWithHeader(
                "PUT",
                customerBase + "/contacts/" + contactId + "/consents/WHATSAPP",
                "If-Match",
                latestContactPolicy.header("etag"),
                "{\"status\":\"REVOKED\",\"source\":\"CUSTOMER_VERBAL\"}"
        ), 200);

        // Dispositions fail with 409 Conflict because customer is now INELIGIBLE
        assertStatus(curlWithHeader(
                "PUT",
                customerBase + "/follow-up-snooze",
                "If-Match",
                "\"10\"",
                "{\"until\":\"" + tomorrow + "\"}"
        ), 409);
        assertStatus(curlRaw(
                "POST",
                customerBase + "/follow-up-dismissals",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: \"10\"",
                        "Idempotency-Key: op-dismiss-stale"
                ),
                null
        ), 409);
        assertStatus(curlRaw(
                "POST",
                customerBase + "/manual-follow-ups",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: \"10\"",
                        "Idempotency-Key: op-manual-stale"
                ),
                null
        ), 409);

        // Step 13: Stale Safeguard - Archiving customer also rejects dispositions with 409 Conflict
        // First re-grant consent so revocation is not the reason
        CurlResult policyBeforeRegrant = curl("GET", customerBase + "/contact-policy", null);
        assertStatus(curlWithHeader(
                "PUT",
                customerBase + "/contacts/" + contactId + "/consents/WHATSAPP",
                "If-Match",
                policyBeforeRegrant.header("etag"),
                "{\"status\":\"GRANTED\",\"source\":\"CUSTOMER_VERBAL\"}"
        ), 200);

        // Archive customer
        CurlResult custToArchive = curl("GET", customerBase, null);
        assertStatus(curlWithHeader(
                "DELETE",
                customerBase,
                "If-Match",
                custToArchive.header("etag"),
                null
        ), 204);

        // Dispositions on archived customer fail with 409 Conflict
        assertStatus(curlWithHeader(
                "PUT",
                customerBase + "/follow-up-snooze",
                "If-Match",
                "\"15\"",
                "{\"until\":\"" + tomorrow + "\"}"
        ), 409);
        assertStatus(curlRaw(
                "POST",
                customerBase + "/manual-follow-ups",
                List.of(
                        "X-Test-Identity: " + operatorId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: \"15\"",
                        "Idempotency-Key: op-manual-archived"
                ),
                null
        ), 409);

        // Step 14: Cross-tenant Isolation - foreign workspace cannot see customer or queue item
        CurlResult foreignQueue = curlAs("GET", "/api/follow-up-queue", foreignOperatorId, foreignTenantId, null);
        assertStatus(foreignQueue, 200);
        assertThat(json.readTree(foreignQueue.body()).get("items")).isEmpty();

        CurlResult foreignCustomerAccess = curlAs("GET", customerBase, foreignOperatorId, foreignTenantId, null);
        assertStatus(foreignCustomerAccess, 404);

        // Step 15: Role-based Boundaries - VIEWER can query queue (200 OK) but is forbidden from dispositions (403)
        CurlResult viewerQueue = curlAs("GET", "/api/follow-up-queue", viewerId, tenantId, null);
        assertStatus(viewerQueue, 200);

        CurlResult viewerSnooze = curlRaw(
                "PUT",
                customerBase + "/follow-up-snooze",
                List.of(
                        "X-Test-Identity: " + viewerId,
                        "X-Tenant-Id: " + tenantId,
                        "If-Match: \"1\""
                ),
                "{\"until\":\"" + tomorrow + "\"}"
        );
        assertStatus(viewerSnooze, 403);
    }

    private CurlResult curl(String method, String path, String body) throws Exception {
        return curlRaw(method, path, List.of("X-Test-Identity: " + operatorId, "X-Tenant-Id: " + tenantId), body);
    }

    private CurlResult curlAs(String method, String path, UUID identity, UUID tenant, String body) throws Exception {
        return curlRaw(method, path, List.of("X-Test-Identity: " + identity, "X-Tenant-Id: " + tenant), body);
    }

    private CurlResult curlWithHeader(String method, String path, String headerName, String headerValue, String body)
            throws Exception {
        return curlRaw(
                method,
                path,
                List.of("X-Test-Identity: " + operatorId, "X-Tenant-Id: " + tenantId, headerName + ": " + headerValue),
                body
        );
    }

    private CurlResult curlRaw(String method, String path, List<String> headers, String body) throws Exception {
        Path headerFile = Files.createTempFile("dokene-op-headers-", ".txt");
        Path bodyFile = Files.createTempFile("dokene-op-body-", ".json");
        try {
            List<String> command = new ArrayList<>(List.of(
                    "curl", "--silent", "--show-error", "--request", method,
                    "--dump-header", headerFile.toString(), "--output", bodyFile.toString(),
                    "--write-out", "%{http_code}", "http://127.0.0.1:" + port + path
            ));
            for (String header : headers) {
                command.addAll(List.of("--header", header));
            }
            if (body != null) {
                command.addAll(List.of("--header", "Content-Type: application/json", "--data", body));
            }
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

    private void assertStatus(CurlResult result, int expected) {
        assertThat(result.status()).isEqualTo(expected);
    }

    record CurlResult(int status, String headers, String body) {
        String header(String name) {
            return headers.lines()
                    .filter(line -> line.regionMatches(true, 0, name + ":", 0, name.length() + 1))
                    .map(line -> line.substring(line.indexOf(':') + 1).strip())
                    .findFirst()
                    .orElse(null);
        }
    }
}
