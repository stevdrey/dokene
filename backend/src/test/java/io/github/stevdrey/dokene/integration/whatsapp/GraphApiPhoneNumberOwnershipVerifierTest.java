// ABOUTME: Drives the ownership verifier against a local stub server: proof, refusal, fail-closed paths and paging.
// ABOUTME: No network beyond loopback; the bearer secret must reach only the Authorization header (issue 210).
package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Claim;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.NotProven;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Outcome;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Proven;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Unavailable;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class GraphApiPhoneNumberOwnershipVerifierTest {
    private static final String PHONE = "100000000000001";
    private static final String WABA = "400000000000001";

    private HttpServer server;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private volatile int status = 200;
    private volatile String body = "";
    private volatile long delayMillis;
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final IntegrationSecretResolver resolver = mock(IntegrationSecretResolver.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<IntegrationSecretResolver> secrets = mock(ObjectProvider.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private GraphApiPhoneNumberOwnershipVerifier verifier;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requested.add(exchange.getRequestURI().toString());
            String query = exchange.getRequestURI().getQuery();
            String reply = body;
            if (!pages.isEmpty()) {
                String after = query != null && query.contains("after=") ? query.substring(query.indexOf("after=") + 6)
                        : "";
                reply = pages.getOrDefault(after, "{\"data\":[],\"paging\":{}}");
            }
            try {
                Thread.sleep(delayMillis);
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (java.io.IOException | InterruptedException clientGone) {
                // The verifier gave up on the exchange: exactly what the deadline is for.
            } finally {
                exchange.close();
            }
        });
        server.start();
        when(contexts.requireCurrent()).thenReturn(new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.ADMIN));
        when(secrets.getIfAvailable()).thenReturn(resolver);
        when(resolver.resolve(any(), any())).thenAnswer(call -> new ResolvedSecret("EAAG-secret".toCharArray()));
        verifier = new GraphApiPhoneNumberOwnershipVerifier(secrets, contexts, new GraphApiHttp(new GraphApiProperties(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v23.0"),
                Duration.ofMillis(500), List.of())));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void provenOnlyWhenTheBusinessAccountListsTheNumberAndOnlyTheBearerCarriesTheSecret() {
        body = page(List.of("100000000000009", PHONE), null);
        assertThat(verify()).isInstanceOf(Proven.class);
        assertThat(authorization.get()).isEqualTo("Bearer EAAG-secret");
        assertThat(requested).singleElement().satisfies(uri -> assertThat(uri)
                .startsWith("/v23.0/" + WABA + "/phone_numbers?").contains("fields=id").doesNotContain("EAAG"));

        ArgumentCaptor<IntegrationSecretResolver.SecretScope> scope =
                ArgumentCaptor.forClass(IntegrationSecretResolver.SecretScope.class);
        org.mockito.Mockito.verify(resolver).resolve(scope.capture(), any());
        assertThat(scope.getValue()).isEqualTo(new Tenant(tenantId));
    }

    @Test
    void aListWithoutTheNumberOrARefusedBusinessAccountIsNotProven() {
        body = page(List.of("100000000000009"), null);
        assertThat(verify()).isInstanceOf(NotProven.class);
        body = "{\"data\":[]}";
        assertThat(verify()).isInstanceOf(NotProven.class);
        // A prefix or a lookalike is not the number.
        body = page(List.of("1000000000000011", "10000000000000"), null);
        assertThat(verify()).isInstanceOf(NotProven.class);
        for (int code : new int[] {400, 403, 404}) {
            status = code;
            body = "{\"error\":{\"message\":\"Unsupported get request\"}}";
            assertThat(verify()).isInstanceOf(NotProven.class);
        }
    }

    @Test
    void anInconclusiveAnswerIsUnavailableWithoutThrowing() {
        // A 401 is an invalid or expired token: a credential problem, not evidence about ownership.
        for (int code : new int[] {302, 401, 429, 500, 503}) {
            status = code;
            body = page(List.of(PHONE), null);
            assertThat(verify()).isInstanceOf(Unavailable.class);
        }
        status = 200;
        for (String malformed : new String[] {"", "not json", "[]", "{}", "{\"data\":{}}"}) {
            body = malformed;
            assertThat(verify()).isInstanceOf(Unavailable.class);
        }
        body = page(List.of(PHONE), null) + " ".repeat(GraphApiHttp.MAX_BODY_BYTES);
        assertThat(verify()).isInstanceOf(Unavailable.class);
        body = page(List.of(PHONE), null);
        delayMillis = 1500;
        assertThat(verify()).isInstanceOf(Unavailable.class);
    }

    @Test
    void aMissingOrFailingResolverOrAForeignTenantNeverCallsTheProvider() {
        body = page(List.of(PHONE), null);
        when(resolver.resolve(any(), any())).thenThrow(new IllegalStateException("EAAG-secret not found"));
        assertThat(verify()).isInstanceOf(Unavailable.class);

        when(secrets.getIfAvailable()).thenReturn(null);
        assertThat(verify()).isInstanceOf(Unavailable.class);

        when(secrets.getIfAvailable()).thenReturn(resolver);
        assertThat(verifier.verify(new Claim(new TenantId(UUID.randomUUID()), PHONE, WABA, "wa-token")))
                .isInstanceOf(Unavailable.class);
        assertThat(verifier.verify(new Claim(tenantId, PHONE, "../me", "wa-token"))).isInstanceOf(Unavailable.class);
        assertThat(requested).isEmpty();
    }

    @Test
    void followsCursorsOnTheSameBaseUrlAndConcludesOnlyWhenTheListEnds() {
        pages.put("", page(List.of("100000000000009"), "CUR2"));
        pages.put("CUR2", page(List.of(PHONE), null));
        assertThat(verify()).isInstanceOf(Proven.class);
        assertThat(requested).hasSize(2).allMatch(uri -> uri.startsWith("/v23.0/" + WABA + "/phone_numbers?"));
        assertThat(requested.get(1)).contains("after=CUR2");

        pages.put("CUR2", page(List.of("100000000000008"), null));
        assertThat(verify()).isInstanceOf(NotProven.class);

        // More pages than the cap: the number might be further on, so no conclusion.
        requested.clear();
        pages.clear();
        pages.put("", page(List.of(), "C1"));
        for (int i = 1; i <= 8; i++) {
            pages.put("C" + i, page(List.of(), "C" + (i + 1)));
        }
        assertThat(verify()).isInstanceOf(Unavailable.class);
        assertThat(requested.size()).isLessThanOrEqualTo(5);

        requested.clear();
        pages.clear();
        pages.put("", page(List.of(), "bad cursor&inject=1"));
        assertThat(verify()).isInstanceOf(Unavailable.class);
        assertThat(requested).hasSize(1);
    }

    private Outcome verify() {
        return verifier.verify(new Claim(tenantId, PHONE, WABA, "wa-token"));
    }

    private static String page(List<String> ids, String after) {
        String data = String.join(",", ids.stream().map(id -> "{\"id\":\"" + id + "\"}").toList());
        String paging = after == null ? "{}" : "{\"next\":\"https://evil.example/steal?x=1\",\"cursors\":{\"after\":\""
                + after + "\"}}";
        return "{\"data\":[" + data + "],\"paging\":" + paging + "}";
    }
}
