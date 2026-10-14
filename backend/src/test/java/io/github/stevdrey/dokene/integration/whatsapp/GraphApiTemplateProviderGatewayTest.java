// ABOUTME: Drives the Graph API gateway against a local stub server: status mapping, fail-closed paths and secret hygiene.
// ABOUTME: No network beyond loopback; the bearer secret must reach only the Authorization header (issue 135).
package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway.Absent;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway.Reported;
import io.github.stevdrey.dokene.template.application.TemplateProviderGateway.Unavailable;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class GraphApiTemplateProviderGatewayTest {
    private static final String WABA = "400000000000001";

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "";
    private volatile long delayMillis;
    private final java.util.Map<String, String> pages = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.List<String> requested = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean endless;
    private volatile long dripMillis;
    private final java.util.concurrent.atomic.AtomicLong bytesWritten = new java.util.concurrent.atomic.AtomicLong();
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final WhatsAppIntegrationRepository integrations = mock(WhatsAppIntegrationRepository.class);
    private final IntegrationSecretResolver resolver = mock(IntegrationSecretResolver.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<IntegrationSecretResolver> secrets = mock(ObjectProvider.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private GraphApiTemplateProviderGateway gateway;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            path.set(exchange.getRequestURI().toString());
            requested.add(exchange.getRequestURI().toString());
            String query = exchange.getRequestURI().getQuery();
            if (!pages.isEmpty()) {
                String after = query != null && query.contains("after=") ? query.substring(query.indexOf("after=") + 6) : "";
                body = pages.getOrDefault(after, "{\"data\":[],\"paging\":{}}");
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try {
                if (endless) {
                    exchange.sendResponseHeaders(status, 0); // chunked
                    byte[] chunk = new byte[8192];
                    java.util.Arrays.fill(chunk, (byte) ' ');
                    while (true) {
                        exchange.getResponseBody().write(chunk);
                        bytesWritten.addAndGet(chunk.length);
                        if (bytesWritten.get() > 64_000_000L) {
                            break;
                        }
                    }
                } else if (dripMillis > 0) {
                    exchange.sendResponseHeaders(status, 0);
                    for (byte b : bytes) {
                        exchange.getResponseBody().write(b);
                        exchange.getResponseBody().flush();
                        Thread.sleep(dripMillis);
                    }
                } else {
                    exchange.sendResponseHeaders(status, bytes.length);
                    exchange.getResponseBody().write(bytes);
                }
            } catch (java.io.IOException | InterruptedException clientGone) {
                // The gateway closed the exchange: exactly what the bound is for.
            } finally {
                exchange.close();
            }
        });
        server.start();
        var integration = WhatsAppIntegration.create(tenantId, "100000000000001", WABA, null, true, "wa-token",
                Instant.now());
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(integration));
        when(contexts.requireCurrent()).thenReturn(new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.ADMIN));
        when(secrets.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(any(), any())).thenAnswer(call -> new ResolvedSecret("EAAG-secret".toCharArray()));
        gateway = new GraphApiTemplateProviderGateway(integrations, secrets, contexts, new GraphApiProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v23.0"),
                Duration.ofMillis(500)));
    }

    @AfterEach
    void stop() {
        endless = false;
        server.stop(0);
    }

    @Test
    void mapsProviderStatusesAndSendsTheTenantsCredentialAsABearerOnly() {
        body = list("repeat_purchase_v1", "es_MX", "APPROVED");
        assertThat(find())
                .isEqualTo(new Reported(TemplateApprovalStatus.APPROVED, WABA));
        assertThat(authorization.get()).isEqualTo("Bearer EAAG-secret");
        assertThat(path.get()).startsWith("/v23.0/" + WABA + "/message_templates?")
                .contains("name=repeat_purchase_v1").contains("language=es_MX").doesNotContain("EAAG");

        for (String[] pair : new String[][] {{"PENDING", "PENDING"}, {"IN_APPEAL", "PENDING"},
                {"REJECTED", "REJECTED"}, {"PAUSED", "PAUSED"}, {"DISABLED", "DISABLED"}, {"DELETED", "DELETED"},
                {"PENDING_DELETION", "DELETED"}, {"ARCHIVED", "DELETED"}}) {
            body = list("repeat_purchase_v1", "es_MX", pair[0]);
            assertThat(find())
                    .isEqualTo(new Reported(TemplateApprovalStatus.valueOf(pair[1]), WABA));
        }
    }

    @Test
    void aMissingTemplateOrOnlyNeighbouringOnesIsAbsent() {
        body = "{\"data\":[]}";
        assertThat(find()).isEqualTo(new Absent(WABA));
        body = list("repeat_purchase_v1", "en_US", "APPROVED"); // same name, other language: no evidence
        assertThat(find()).isEqualTo(new Absent(WABA));
        body = list("repeat_purchase_v2", "es_MX", "APPROVED");
        assertThat(find()).isEqualTo(new Absent(WABA));
    }

    @Test
    void everyFailureIsUnavailableWithoutThrowing() {
        for (int code : new int[] {400, 401, 403, 404, 429, 500, 503}) {
            status = code;
            body = list("repeat_purchase_v1", "es_MX", "APPROVED");
            assertThat(find()).isInstanceOf(Unavailable.class);
        }
        status = 200;
        for (String malformed : new String[] {"", "not json", "[]", "{}", "{\"data\":{}}",
                list("repeat_purchase_v1", "es_MX", "LIMIT_EXCEEDED"), list("repeat_purchase_v1", "es_MX", "WEIRD"),
                "{\"data\":[{\"name\":\"repeat_purchase_v1\",\"language\":\"es_MX\"}]}"}) {
            body = malformed;
            assertThat(find()).isInstanceOf(Unavailable.class);
        }
    }

    @Test
    void timeoutsUnresolvableSecretsAndMissingResolverFailClosed() {
        body = list("repeat_purchase_v1", "es_MX", "APPROVED");
        delayMillis = 1500;
        assertThat(find()).isInstanceOf(Unavailable.class);
        delayMillis = 0;

        when(resolver.resolve(any(), any())).thenThrow(new IllegalStateException("EAAG-secret not found"));
        assertThat(find()).isInstanceOf(Unavailable.class);

        when(secrets.getIfAvailable()).thenReturn(null);
        authorization.set(null);
        assertThat(find()).isInstanceOf(Unavailable.class);
        assertThat(authorization.get()).isNull();

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThat(gateway.currentSender()).isEmpty();
        assertThat(gateway.lookup(TemplateProviderGateway.Sender.of(UUID.randomUUID(), WABA), "repeat_purchase_v1",
                "es_MX")).isInstanceOf(Unavailable.class);
    }

    @Test
    void anOversizedOrEndlessBodyIsUnavailableAndIsNotReadInFull() {
        body = list("repeat_purchase_v1", "es_MX", "APPROVED") + " ".repeat(300_000);
        assertThat(find()).isInstanceOf(Unavailable.class);

        endless = true; // chunked body that never ends
        assertThat(find()).isInstanceOf(Unavailable.class);
        assertThat(bytesWritten.get()).isLessThan(8_000_000L);
    }

    @Test
    void aBodyJustUnderTheCapStillMapsAndASlowDripIsUnavailable() {
        String exact = list("repeat_purchase_v1", "es_MX", "APPROVED");
        body = exact + " ".repeat(262_144 - exact.length());
        assertThat(body.length()).isEqualTo(262_144);
        assertThat(find())
                .isEqualTo(new Reported(TemplateApprovalStatus.APPROVED, WABA));

        body = exact;
        dripMillis = 400; // headers arrive at once, then the body trickles past the 500 ms timeout
        long started = System.nanoTime();
        assertThat(find()).isInstanceOf(Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void oneDeadlineCoversTheHeadersAndTheBody() {
        body = list("repeat_purchase_v1", "es_MX", "APPROVED");
        // Headers take ~350 ms and the body ~(length x 4 ms): each alone fits the 500 ms timeout, together they do not.
        delayMillis = 350;
        dripMillis = 4;
        assertThat(body.length() * dripMillis).isBetween(200L, 480L);
        long started = System.nanoTime();
        assertThat(find()).isInstanceOf(Unavailable.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(900));
    }

    @Test
    void followsCursorsOnTheSameBaseUrlAndFindsAMatchOnALaterPage() {
        pages.put("", page(template("repeat_purchase_v10", "es_MX", "APPROVED"), "CUR2"));
        pages.put("CUR2", page(template("repeat_purchase_v1", "es_MX", "PAUSED"), null));
        assertThat(find()).isEqualTo(new Reported(TemplateApprovalStatus.PAUSED, WABA));
        assertThat(requested).hasSize(2);
        assertThat(requested.get(1)).startsWith("/v23.0/" + WABA + "/message_templates?").contains("after=CUR2");
    }

    @Test
    void absenceIsConcludedOnlyWhenTheListEnds() {
        pages.put("", page(template("repeat_purchase_v10", "es_MX", "APPROVED"), "CUR2"));
        pages.put("CUR2", page(template("other", "es_MX", "APPROVED"), null));
        assertThat(find()).isEqualTo(new Absent(WABA));

        // More pages than the cap: the template might be further on, so no conclusion.
        requested.clear();
        pages.clear();
        pages.put("", page("", "C1"));
        for (int i = 1; i <= 8; i++) {
            pages.put("C" + i, page("", "C" + (i + 1)));
        }
        assertThat(find()).isInstanceOf(Unavailable.class);
        assertThat(requested.size()).isLessThanOrEqualTo(5);
    }

    @Test
    void neverFollowsTheResponsesNextUrlAndRefusesUnsafeCursors() {
        pages.put("", page("", "CUR2"));
        find();
        assertThat(requested).allMatch(uri -> uri.startsWith("/v23.0/" + WABA + "/message_templates?"));

        requested.clear();
        pages.clear();
        pages.put("", page("", "bad cursor&inject=1"));
        assertThat(find()).isInstanceOf(Unavailable.class);
        assertThat(requested).hasSize(1);
    }

    @Test
    void oneDeadlineAlsoCoversSeveralSlowPages() {
        pages.put("", page(template("other", "es_MX", "APPROVED"), "CUR2"));
        pages.put("CUR2", page(template("repeat_purchase_v1", "es_MX", "APPROVED"), null));
        delayMillis = 300; // each page fits the 500 ms timeout, two together do not
        assertThat(find()).isInstanceOf(Unavailable.class);
    }

    @Test
    void aSenderThatDidNotComeFromThisGatewayIsNeverUsed() {
        body = list("repeat_purchase_v1", "es_MX", "APPROVED");
        assertThat(gateway.lookup(TemplateProviderGateway.Sender.of(UUID.randomUUID(), WABA), "repeat_purchase_v1",
                "es_MX")).isInstanceOf(Unavailable.class);
        assertThat(requested).isEmpty();
    }

    @Test
    void redirectsAreNotFollowed() {
        status = 302;
        body = "";
        assertThat(find()).isInstanceOf(Unavailable.class);
    }

    /** Looks up the stored integration's sender the way the service does, then asks the gateway. */
    private TemplateProviderGateway.Lookup find() {
        var sender = gateway.currentSender();
        return gateway.lookup(sender.orElseGet(() -> TemplateProviderGateway.Sender.of(UUID.randomUUID(), WABA)),
                "repeat_purchase_v1", "es_MX");
    }

    private static String page(String templates, String after) {
        String paging = after == null ? "{}" : "{\"next\":\"https://evil.example/steal?x=1\",\"cursors\":{\"after\":\""
                + after + "\"}}";
        return "{\"data\":[" + templates + "],\"paging\":" + paging + "}";
    }

    private static String template(String name, String language, String status) {
        return "{\"name\":\"" + name + "\",\"language\":\"" + language + "\",\"status\":\"" + status + "\"}";
    }

    private static String list(String name, String language, String status) {
        return "{\"data\":[{\"name\":\"" + name + "\",\"language\":\"" + language + "\",\"status\":\"" + status
                + "\",\"id\":\"1\"}]}";
    }
}
