// ABOUTME: Drives the provider health probe against a loopback stub (ADR 0038): which answers pass or fail it, when
// ABOUTME: there is no result at all, and that it only ever sends one GET with the tenant's own credential.
package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.WhatsAppIntegrationRepository;
import io.github.stevdrey.dokene.integration.application.WhatsAppOutboundSafetyControl;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;

class GraphApiReachabilityProbeTest {
    private static final String PHONE = "100000000000001";
    private static final String WABA = "400000000000001";
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long delayMillis;
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final WhatsAppIntegrationRepository integrations = mock(WhatsAppIntegrationRepository.class);
    private final IntegrationSecretResolver resolver = mock(IntegrationSecretResolver.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<IntegrationSecretResolver> secrets = mock(ObjectProvider.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private WhatsAppIntegration integration;
    private SenderRevision sender;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                Thread.sleep(delayMillis);
                byte[] bytes = "{\"data\":[{\"id\":\"100000000000001\"}]}".getBytes(StandardCharsets.UTF_8);
                if (status == 302) {
                    exchange.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
                }
                exchange.sendResponseHeaders(status, status == 200 ? bytes.length : -1);
                if (status == 200) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException | InterruptedException clientGone) {
                // The probe gave up on the exchange: exactly what the deadline is for.
            } finally {
                exchange.close();
            }
        });
        server.start();
        Instant now = Instant.now();
        integration = WhatsAppIntegration.create(tenantId, PHONE, WABA, "+50600000000", true, "wa-token", now)
                .verifyOwnership(now).withOutboundEnabled(true, now);
        sender = WhatsAppOutboundSafetyControl.senderRevision(integration);
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(integration));
        when(contexts.requireCurrent()).thenReturn(new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.OPERATOR));
        when(secrets.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(any(), any())).thenAnswer(call -> new ResolvedSecret("EAAG-secret".toCharArray()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** covers: spec 0023 AC-6. */
    @ParameterizedTest
    @CsvSource({"200,PASSED", "400,PASSED", "401,PASSED", "403,PASSED", "404,PASSED", "429,PASSED_THROTTLED",
            "302,FAILED", "500,FAILED", "503,FAILED"})
    void theStatusOfTheOneReadDecidesTheResult(int answered, ProviderProbeResult expected) {
        status = answered;

        assertThat(probe().probe(tenantId, sender)).isEqualTo(expected);

        // One GET of the first page of the tenant's own number list, never a send and never a followed redirect.
        assertThat(requested).containsExactly("GET /v23.0/" + WABA + "/phone_numbers?fields=id&limit=100");
        assertThat(authorization.get()).isEqualTo("Bearer EAAG-secret");
    }

    @Test
    void anAnswerSlowerThanTheGraphDeadlineFailsTheProbe() {
        delayMillis = TIMEOUT.toMillis() * 3;

        long started = System.nanoTime();
        assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.FAILED);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(TIMEOUT.multipliedBy(3));
    }

    @Test
    void aRefusedConnectionFailsTheProbe() throws Exception {
        int closedPort;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = closed.getLocalPort();
        }

        assertThat(probe(closedPort).probe(tenantId, sender)).isEqualTo(ProviderProbeResult.FAILED);
    }

    @Test
    void aCredentialThatDoesNotResolveGivesNoResultAndNoRequest() {
        when(resolver.resolve(any(), any())).thenThrow(new IllegalStateException("unresolvable"));
        assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);

        when(secrets.getIfAvailable()).thenReturn(null);
        assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);

        assertThat(requested).isEmpty();
    }

    @Test
    void anIntegrationThatChangedSinceThePolicyReadItGivesNoResultAndResolvesNoSecret() {
        when(integrations.findByTenant(tenantId))
                .thenReturn(Optional.of(integration.rotateCredential(Instant.now())));
        assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);

        when(integrations.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);

        verify(resolver, never()).resolve(any(), any());
        assertThat(requested).isEmpty();
    }

    /** The same three conditions the send adapter requires before it uses the tenant's credential. */
    @ParameterizedTest
    @CsvSource({"false,true,true", "true,false,true", "true,true,false"})
    void anIntegrationThatMayNoLongerSendGivesNoResultAndResolvesNoSecret(boolean enabled, boolean outboundEnabled,
            boolean ownershipVerified) {
        when(integrations.findByTenant(tenantId)).thenReturn(Optional.of(new WhatsAppIntegration(integration.id(),
                tenantId, PHONE, WABA, integration.displayPhoneNumber(), enabled, outboundEnabled,
                integration.secretRef(), integration.credentialRevision(), integration.createdAt(),
                integration.updatedAt(), integration.version(),
                ownershipVerified ? integration.ownershipVerifiedAt() : null)));

        assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);

        verify(resolver, never()).resolve(any(), any());
        assertThat(requested).isEmpty();
    }

    @Test
    void aTenantOtherThanTheOneInContextGivesNoResult() {
        assertThat(probe().probe(new TenantId(UUID.randomUUID()), sender)).isEqualTo(ProviderProbeResult.NO_RESULT);

        verify(resolver, never()).resolve(any(), any());
        assertThat(requested).isEmpty();
    }

    @Test
    void aCallWithNoTenantInContextGivesNoResultAndResolvesNoSecret() {
        when(contexts.requireCurrent()).thenThrow(new IllegalStateException("no tenant context"));

        assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);

        verify(resolver, never()).resolve(any(), any());
        assertThat(requested).isEmpty();
    }

    @Test
    void aMissingTenantOrSenderGivesNoResultAndResolvesNoSecret() {
        assertThat(probe().probe(null, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);
        assertThat(probe().probe(tenantId, null)).isEqualTo(ProviderProbeResult.NO_RESULT);

        verify(resolver, never()).resolve(any(), any());
        assertThat(requested).isEmpty();
    }

    @Test
    void aDeadlineAlreadyUsedUpGivesNoResult() {
        when(resolver.resolve(any(), any())).thenAnswer(call -> {
            Thread.sleep(TIMEOUT.toMillis() + 100);
            return new ResolvedSecret("EAAG-secret".toCharArray());
        });

        assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);
        assertThat(requested).isEmpty();
    }

    @Test
    void anInterruptedProbeGivesNoResult() {
        delayMillis = TIMEOUT.toMillis() * 3;
        Thread.currentThread().interrupt();
        try {
            assertThat(probe().probe(tenantId, sender)).isEqualTo(ProviderProbeResult.NO_RESULT);
        } finally {
            Thread.interrupted();
        }
    }

    private GraphApiReachabilityProbe probe() {
        return probe(server.getAddress().getPort());
    }

    private GraphApiReachabilityProbe probe(int port) {
        return new GraphApiReachabilityProbe(integrations, secrets, contexts, new GraphApiHttp(new GraphApiProperties(
                URI.create("http://127.0.0.1:" + port + "/v23.0"), TIMEOUT, List.of())));
    }
}
