// ABOUTME: End to end journey for specs 0002 and 0003 over real HTTP with curl: submit, list, detail, approve, send.
// ABOUTME: Configures the tenant integration over HTTP first (spec 0005 AC-11) and proves a VIEWER gets the problem body.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.integration.config.IntegrationConfigurationService;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
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
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "dokene.ai.provider=fake", "dokene.messaging.template-gate.stub.enabled-intents=GENERAL_FOLLOW_UP",
        "dokene.messaging.provider=local", "dokene.messaging.outbound.enabled=true",
        "dokene.messaging.local.delivery-delay=0s"})
@Import(PurchaseCurlSystemTest.CurlSecurityConfiguration.class)
class MessageCurlSystemTest {
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra.";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @LocalServerPort int port;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @Autowired CustomerService customers;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired FollowUpService followUps;
    @Autowired ObjectMapper json;
    @Autowired IntegrationConfigurationService integrations;
    @Autowired ConfigurableEnvironment environment;

    private UUID tenantId;
    private UUID ownerIdentity;
    private UUID viewerIdentity;
    private UUID customerId;
    private UUID contactId;
    private long policyVersion;

    @BeforeEach
    void seed() throws Exception {
        Instant now = Instant.now();
        var tenant = seedTenant(tenants, contexts, "Messages curl " + UUID.randomUUID(), now);
        tenantId = tenant.id().value();
        var owner = new IdentityId(UUID.randomUUID());
        TenantContext ownerContext = context(seedMembership(memberships, contexts, tenant.id(), owner,
                TenantRole.OWNER, now));
        ownerIdentity = owner.value();
        var viewer = new IdentityId(UUID.randomUUID());
        seedMembership(memberships, contexts, tenant.id(), viewer, TenantRole.VIEWER, now);
        viewerIdentity = viewer.value();

        String number = "8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);
        Customer customer = inContext(ownerContext, () -> customers.create("Cliente curl", null,
                List.of(new PhoneInput(number, "CR", true))));
        customerId = customer.id().value();
        contactId = customer.phones().getFirst().id();
        var policy = inContext(ownerContext, () -> contacts.get(customer.id()));
        inContext(ownerContext, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(ownerContext, () -> purchases.record(customer.id(), now.minusSeconds(60), "Anchor purchase",
                "anchor-" + UUID.randomUUID()));
        inContext(ownerContext, () -> followUps.configureTenant(30, ZoneId.of("UTC"), 0));
        inContext(ownerContext, () -> followUps.configureCustomer(customer.id(), null,
                LocalDate.now(ZoneId.of("UTC")), 0));
        var snapshot = inContext(ownerContext, () -> followUps.evaluateSnapshot(customer.id()));
        assertThat(snapshot.evaluation().status()).isEqualTo(FollowUpStatus.DUE);
        policyVersion = snapshot.policyVersion();
    }

    @Test
    void walksSubmitListDetailEventsApproveSendAndDeliveryOverHttp() throws Exception {
        // Spec 0005: the tenant integration is configured over the real routes before the send; the secret reference
        // names a test property, and the response carries the reference string, never the token value.
        String secretRef = new IntegrationTestSupport(integrations, contexts, auditExecution, environment)
                .registerSecret("curl-test-token");
        CurlResult blank = curlAs("GET", "/api/integrations/whatsapp", ownerIdentity, List.of(), null);
        assertThat(blank.status()).isEqualTo(200);
        assertThat(blank.headers()).contains("ETag: \"0\"");
        assertThat(json.readTree(blank.body()).get("version").asLong()).isZero();
        String phoneNumberId = "1" + String.format("%014d", Math.abs(UUID.randomUUID().getLeastSignificantBits()
                % 100_000_000_000_000L));
        CurlResult configured = curlAs("PUT", "/api/integrations/whatsapp", ownerIdentity, List.of("If-Match: \"0\""),
                """
                {"phoneNumberId":"%s","businessAccountId":"200000000000000","displayPhoneNumber":"+56 9 2222 0000",
                 "displayPhoneRegion":"CL","secretRef":"%s","enabled":true}
                """.formatted(phoneNumberId, secretRef));
        assertThat(configured.status()).isEqualTo(200);
        assertThat(configured.headers()).contains("ETag: \"1\"");
        JsonNode integration = json.readTree(configured.body());
        assertThat(integration.get("displayPhoneNumber").asString()).isEqualTo("+56922220000");
        assertThat(integration.get("secretRef").asString()).isEqualTo(secretRef);
        assertThat(integration.get("outboundEnabled").asBoolean()).isFalse();
        assertThat(configured.body()).doesNotContain("curl-test-token");
        CurlResult switched = curlAs("PUT", "/api/integrations/whatsapp/outbound", ownerIdentity,
                List.of("If-Match: \"1\""), "{\"enabled\":true}");
        assertThat(switched.status()).isEqualTo(200);
        assertThat(switched.headers()).contains("ETag: \"2\"");
        assertThat(json.readTree(switched.body()).get("outboundEnabled").asBoolean()).isTrue();
        CurlResult viewerSwitch = curlAs("PUT", "/api/integrations/whatsapp/outbound", viewerIdentity,
                List.of("If-Match: \"2\""), "{\"enabled\":false}");
        assertThat(viewerSwitch.status()).isEqualTo(403);
        assertThat(viewerSwitch.body()).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\"FORBIDDEN\"}");

        String submitBody = """
                {"contactId":"%s","action":"GENERAL_CHECK_IN","templateIntent":"GENERAL_FOLLOW_UP",
                 "body":"%s","locale":"es-419","origin":"MANUAL"}
                """.formatted(contactId, BODY);
        String submitKey = "submit-" + UUID.randomUUID();
        CurlResult submitted = curlAs("POST", "/api/customers/" + customerId + "/messages", ownerIdentity,
                List.of("If-Match: \"" + policyVersion + "\"", "Idempotency-Key: " + submitKey), submitBody);
        assertThat(submitted.status()).isEqualTo(201);
        JsonNode message = json.readTree(submitted.body());
        String messageId = message.get("messageId").asString();
        assertThat(submitted.headers()).contains("Location: /api/messages/" + messageId).contains("ETag: \"1\"");
        assertThat(message.get("status").asString()).isEqualTo("PENDING_APPROVAL");
        assertThat(message.get("customerDisplayName").asString()).isEqualTo("Cliente curl");

        CurlResult replay = curlAs("POST", "/api/customers/" + customerId + "/messages", ownerIdentity,
                List.of("If-Match: \"" + policyVersion + "\"", "Idempotency-Key: " + submitKey), submitBody);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.headers()).doesNotContain("Location:");

        CurlResult list = curlAs("GET", "/api/messages?status=PENDING_APPROVAL", ownerIdentity, List.of(), null);
        assertThat(list.status()).isEqualTo(200);
        JsonNode items = json.readTree(list.body()).get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("messageId").asString()).isEqualTo(messageId);
        assertThat(items.get(0).has("body")).isFalse();

        CurlResult detail = curlAs("GET", "/api/messages/" + messageId, ownerIdentity, List.of(), null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.headers()).contains("ETag: \"1\"");
        assertThat(json.readTree(detail.body()).get("body").asString()).isEqualTo(BODY);

        CurlResult approved = curlAs("POST", "/api/messages/" + messageId + "/approvals", ownerIdentity,
                List.of("If-Match: \"1\"", "Idempotency-Key: approve-" + UUID.randomUUID()),
                "{\"decision\":\"APPROVE\",\"note\":\"ok\"}");
        assertThat(approved.status()).isEqualTo(201);
        assertThat(approved.headers()).contains("ETag: \"2\"");
        assertThat(json.readTree(approved.body()).get("approval").get("note").asString()).isEqualTo("ok");

        String sendKey = "send-" + UUID.randomUUID();
        CurlResult sent = curlAs("POST", "/api/messages/" + messageId + "/sends", ownerIdentity,
                List.of("If-Match: \"2\"", "Idempotency-Key: " + sendKey), null);
        assertThat(sent.status()).isEqualTo(201);
        JsonNode sentBody = json.readTree(sent.body());
        assertThat(sentBody.get("status").asString()).isIn("SENT", "DELIVERED");
        assertThat(sentBody.get("providerMessageId").asString()).startsWith("local-");
        assertThat(sentBody.get("attempts").size()).isEqualTo(1);
        assertThat(sentBody.get("lastAttempt").get("outcome").asString()).isEqualTo("ACCEPTED");
        assertThat(sent.headers()).contains("ETag: \"" + sentBody.get("version").asLong() + "\"");

        CurlResult sendReplay = curlAs("POST", "/api/messages/" + messageId + "/sends", ownerIdentity,
                List.of("If-Match: \"2\"", "Idempotency-Key: " + sendKey), null);
        assertThat(sendReplay.status()).isEqualTo(200);

        JsonNode delivered = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            CurlResult poll = curlAs("GET", "/api/messages/" + messageId, ownerIdentity, List.of(), null);
            assertThat(poll.status()).isEqualTo(200);
            delivered = json.readTree(poll.body());
            if (delivered.get("status").asString().equals("DELIVERED")) break;
            Thread.sleep(200);
        }
        assertThat(delivered).isNotNull();
        assertThat(delivered.get("status").asString()).isEqualTo("DELIVERED");
        assertThat(delivered.get("deliveredAt").isNull()).isFalse();

        CurlResult cancelled = curlAs("POST", "/api/messages/" + messageId + "/cancellations", ownerIdentity,
                List.of("If-Match: \"" + delivered.get("version").asLong() + "\"",
                        "Idempotency-Key: cancel-" + UUID.randomUUID()), null);
        assertThat(cancelled.status()).isEqualTo(409);
        assertThat(cancelled.body()).contains("\"code\":\"INVALID_TRANSITION\"");

        CurlResult events = curlAs("GET", "/api/messages/" + messageId + "/events", ownerIdentity, List.of(), null);
        assertThat(events.status()).isEqualTo(200);
        JsonNode rows = json.readTree(events.body()).get("events");
        assertThat(rows.size()).isEqualTo(6);
        assertThat(rows.get(5).get("statusTo").asString()).isEqualTo("DELIVERED");
        assertThat(rows.get(5).get("actorKind").asString()).isEqualTo("PROVIDER");
        assertThat(events.body()).doesNotContain("ok\"").doesNotContain(BODY)
                .doesNotContain(sentBody.get("providerMessageId").asString());

        CurlResult denied = curlAs("POST", "/api/customers/" + customerId + "/messages", viewerIdentity,
                List.of(), null);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.headers()).containsIgnoringCase("Content-Type: application/problem+json");
        assertThat(denied.body()).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,\"code\":\"FORBIDDEN\"}");

        CurlResult stale = curlAs("POST", "/api/messages/" + messageId + "/approvals", ownerIdentity,
                List.of("If-Match: 1", "Idempotency-Key: approve-" + UUID.randomUUID()), "{\"decision\":\"APPROVE\"}");
        assertThat(stale.status()).isEqualTo(400);
        assertThat(stale.body()).contains("\"code\":\"INVALID_INPUT\"");
    }

    private CurlResult curlAs(String method, String path, UUID identity, List<String> extraHeaders, String body)
            throws Exception {
        List<String> headers = new ArrayList<>(List.of("X-Test-Identity: " + identity, "X-Tenant-Id: " + tenantId));
        headers.addAll(extraHeaders);
        return curlRaw(method, path, headers, body);
    }

    private CurlResult curlRaw(String method, String path, List<String> headers, String body) throws Exception {
        Path headerFile = Files.createTempFile("dokene-message-curl-headers-", ".txt");
        Path bodyFile = Files.createTempFile("dokene-message-curl-body-", ".json");
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

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(), () -> contexts.callWithContext(context, operation::call));
    }

    record CurlResult(int status, String headers, String body) { }
}
