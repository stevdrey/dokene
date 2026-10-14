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
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
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
        server.stop(0);
    }

    @Test
    void mapsProviderStatusesAndSendsTheTenantsCredentialAsABearerOnly() {
        body = list("repeat_purchase_v1", "es_MX", "APPROVED");
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX"))
                .isEqualTo(new Reported(TemplateApprovalStatus.APPROVED, WABA));
        assertThat(authorization.get()).isEqualTo("Bearer EAAG-secret");
        assertThat(path.get()).startsWith("/v23.0/" + WABA + "/message_templates?")
                .contains("name=repeat_purchase_v1").contains("language=es_MX").doesNotContain("EAAG");

        for (String[] pair : new String[][] {{"PENDING", "PENDING"}, {"IN_APPEAL", "PENDING"},
                {"REJECTED", "REJECTED"}, {"PAUSED", "PAUSED"}, {"DISABLED", "DISABLED"}, {"DELETED", "DELETED"},
                {"PENDING_DELETION", "DELETED"}, {"ARCHIVED", "DELETED"}}) {
            body = list("repeat_purchase_v1", "es_MX", pair[0]);
            assertThat(gateway.lookup("repeat_purchase_v1", "es_MX"))
                    .isEqualTo(new Reported(TemplateApprovalStatus.valueOf(pair[1]), WABA));
        }
    }

    @Test
    void aMissingTemplateOrOnlyNeighbouringOnesIsAbsent() {
        body = "{\"data\":[]}";
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isEqualTo(new Absent(WABA));
        body = list("repeat_purchase_v1", "en_US", "APPROVED"); // same name, other language: no evidence
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isEqualTo(new Absent(WABA));
        body = list("repeat_purchase_v2", "es_MX", "APPROVED");
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isEqualTo(new Absent(WABA));
    }

    @Test
    void everyFailureIsUnavailableWithoutThrowing() {
        for (int code : new int[] {400, 401, 403, 404, 429, 500, 503}) {
            status = code;
            body = list("repeat_purchase_v1", "es_MX", "APPROVED");
            assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isInstanceOf(Unavailable.class);
        }
        status = 200;
        for (String malformed : new String[] {"", "not json", "[]", "{}", "{\"data\":{}}",
                list("repeat_purchase_v1", "es_MX", "LIMIT_EXCEEDED"), list("repeat_purchase_v1", "es_MX", "WEIRD"),
                "{\"data\":[{\"name\":\"repeat_purchase_v1\",\"language\":\"es_MX\"}]}"}) {
            body = malformed;
            assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isInstanceOf(Unavailable.class);
        }
    }

    @Test
    void timeoutsUnresolvableSecretsAndMissingResolverFailClosed() {
        body = list("repeat_purchase_v1", "es_MX", "APPROVED");
        delayMillis = 1500;
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isInstanceOf(Unavailable.class);
        delayMillis = 0;

        when(resolver.resolve(any(), any())).thenThrow(new IllegalStateException("EAAG-secret not found"));
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isInstanceOf(Unavailable.class);

        when(secrets.getIfAvailable()).thenReturn(null);
        authorization.set(null);
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isInstanceOf(Unavailable.class);
        assertThat(authorization.get()).isNull();

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThat(gateway.currentSender()).isEmpty();
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isInstanceOf(Unavailable.class);
    }

    @Test
    void redirectsAreNotFollowed() {
        status = 302;
        body = "";
        assertThat(gateway.lookup("repeat_purchase_v1", "es_MX")).isInstanceOf(Unavailable.class);
    }

    private static String list(String name, String language, String status) {
        return "{\"data\":[{\"name\":\"" + name + "\",\"language\":\"" + language + "\",\"status\":\"" + status
                + "\",\"id\":\"1\"}]}";
    }
}
