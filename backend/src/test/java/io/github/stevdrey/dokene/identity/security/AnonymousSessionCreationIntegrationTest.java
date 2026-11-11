// ABOUTME: Sweeps the BFF chain over real HTTP as an anonymous client (issue 311, audit M2): no route in the sweep
// ABOUTME: may create a server session or issue a JSESSIONID cookie, and each keeps the status it had before the fix.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SessionTracker.class)
class AnonymousSessionCreationIntegrationTest {

    private static final Map<String, String> NO_HEADERS = Map.of();
    private static final Map<String, String> MADE_UP_CSRF = Map.of("X-CSRF-TOKEN", "made-up-csrf-token");
    private static final Map<String, String> MADE_UP_SESSION = Map.of("Cookie", "JSESSIONID=made-up-session-id");
    private static final String FORM = "application/x-www-form-urlencoded";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        OidcProviderStub.register(registry);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SessionTracker sessions;

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /** Each expected status was recorded against the code before the fix and must not change. */
    static Stream<Probe> anonymousSweep() {
        List<Probe> probes = new ArrayList<>(List.of(
                new Probe("GET", "/api/session", Map.of("Accept", "text/html"), 401),
                new Probe("GET", "/api/session", Map.of("Accept", "application/json"), 401),
                new Probe("GET", "/api/session", MADE_UP_SESSION, 401),
                new Probe("HEAD", "/api/session", NO_HEADERS, 401),
                new Probe("OPTIONS", "/api/customers", Map.of("Origin", "http://localhost:5173",
                        "Access-Control-Request-Method", "POST"), 403),
                new Probe("GET", "/api/customers", NO_HEADERS, 401),
                new Probe("GET", "/api/tenants", NO_HEADERS, 401),
                new Probe("GET", "/api/unknown-route", NO_HEADERS, 401),
                new Probe("GET", "/unknown-route", NO_HEADERS, 401),
                new Probe("GET", "/logout", NO_HEADERS, 401),
                new Probe("POST", "/api/customers", MADE_UP_SESSION, 401),
                new Probe("POST", "/logout", NO_HEADERS, 403),
                new Probe("POST", "/logout/connect/back-channel/dokene", Map.of("Content-Type", FORM), 400,
                        "logout_token=not-a-jwt"),
                new Probe("GET", "/login/oauth2/code/dokene?code=x&state=y", NO_HEADERS, 302),
                new Probe("GET", "/oauth2/authorization/unknown", NO_HEADERS, 500),
                new Probe("GET", "/login/x", NO_HEADERS, 404),
                new Probe("GET", "/oauth2/x", NO_HEADERS, 404),
                new Probe("POST", "/login/oauth2/code/dokene", NO_HEADERS, 403),
                new Probe("POST", "/oauth2/authorization/dokene", NO_HEADERS, 403),
                new Probe("GET", "/", NO_HEADERS, 404),
                new Probe("GET", "/error", NO_HEADERS, 500)));
        for (String path : List.of("/api/customers", "/api/unknown-route")) {
            for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
                probes.add(new Probe(method, path, NO_HEADERS, 401));
                probes.add(new Probe(method, path, MADE_UP_CSRF, 401));
            }
        }
        return probes.stream();
    }

    /** covers: AC-1. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("anonymousSweep")
    void anAnonymousRequestCreatesNoSession(Probe probe) throws Exception {
        int sessionsBefore = sessions.created();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + probe.path()))
                .method(probe.method(), probe.body() == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(probe.body()));
        probe.headers().forEach(request::header);

        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(response.statusCode()).as("status").isEqualTo(probe.expectedStatus());
            softly.assertThat(response.headers().allValues("Set-Cookie")).as("Set-Cookie")
                    .noneMatch(cookie -> cookie.startsWith("JSESSIONID="));
            softly.assertThat(sessions.created() - sessionsBefore).as("sessions created").isZero();
        });
    }

    /**
     * covers: AC-1. The same bytes as the container health check (infra/docker/backend/Dockerfile): HTTP/1.0 with no
     * Host and no Accept header, which no HTTP client library will send.
     */
    @Test
    void theContainerHealthCheckRequestCreatesNoSession() throws Exception {
        int sessionsBefore = sessions.created();
        String response;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write("GET /api/session HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            response = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }

        assertThat(response.lines().findFirst().orElseThrow()).contains(" 401 ");
        assertThat(response).doesNotContainIgnoringCase("Set-Cookie: JSESSIONID");
        assertThat(sessions.created() - sessionsBefore).isZero();
    }

    record Probe(String method, String path, Map<String, String> headers, int expectedStatus, String body) {

        Probe(String method, String path, Map<String, String> headers, int expectedStatus) {
            this(method, path, headers, expectedStatus, null);
        }

        /** The display name of the parameterized run; header values are synthetic. */
        @Override
        public String toString() {
            return method + " " + path + (headers.isEmpty() ? "" : " " + headers.keySet());
        }
    }
}
