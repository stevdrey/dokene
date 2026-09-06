package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(OidcBrowserSessionIntegrationTest.SessionExpirationController.class)
class OidcBrowserSessionIntegrationTest {

    private static final String CLIENT_ID = "dokene-browser-test";
    private static final String CLIENT_SECRET = "test-secret";
    private static final String SUBJECT = "browser-user";
    private static final OidcStub OIDC = new OidcStub();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        OIDC.start();
        registry.add("spring.security.oauth2.client.registration.dokene.provider", () -> "dokene");
        registry.add("spring.security.oauth2.client.registration.dokene.client-id", () -> CLIENT_ID);
        registry.add("spring.security.oauth2.client.registration.dokene.client-secret", () -> CLIENT_SECRET);
        registry.add("spring.security.oauth2.client.registration.dokene.scope", () -> "openid");
        registry.add("spring.security.oauth2.client.registration.dokene.authorization-grant-type",
                () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.dokene.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.provider.dokene.issuer-uri", OIDC::issuer);
    }

    @AfterAll
    static void stopOidcProvider() {
        OIDC.stop();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void validAuthorizationCodeCreatesAReusableInternalIdentityAndExposesOnlySessionContract() throws Exception {
        Browser firstBrowser = browser();
        HttpResponse<String> firstCallback = authenticate(firstBrowser, TokenMode.VALID);
        assertThat(firstCallback.statusCode()).isEqualTo(302);
        assertThat(countMappings()).isEqualTo(1);
        assertThat(firstCallback.headers().firstValue("Location").orElseThrow()).endsWith("/api/session");

        HttpResponse<String> firstSession = get(firstBrowser, "/api/session");
        assertThat(firstSession.statusCode()).isEqualTo(200);
        JsonNode firstBody = objectMapper.readTree(firstSession.body());
        assertThat(firstBody.propertyNames())
                .containsExactlyInAnyOrder("authenticated", "identityId", "csrfToken");
        assertThat(firstBody.path("authenticated").asBoolean()).isTrue();
        assertThat(firstBody.path("csrfToken").asText()).isNotBlank();

        Browser secondBrowser = browser();
        authenticate(secondBrowser, TokenMode.VALID);
        JsonNode secondBody = objectMapper.readTree(get(secondBrowser, "/api/session").body());

        assertThat(secondBody.path("identityId").asText()).isEqualTo(firstBody.path("identityId").asText());
        assertThat(countMappings()).isEqualTo(1);
        assertThat(firstSession.body()).doesNotContain("access_token", "id_token", "refresh_token");
    }

    @Test
    void rejectsInvalidStateIssuerAndExpiredTokenWithoutCreatingAnAuthenticatedSession() throws Exception {
        int mappingsBefore = countMappings();

        Browser invalidStateBrowser = browser();
        HttpResponse<String> authorization = beginAuthentication(invalidStateBrowser);
        HttpResponse<String> providerRedirect = getAbsolute(invalidStateBrowser, authorization.headers()
                .firstValue("Location").orElseThrow() + "&mode=VALID");
        URI callback = URI.create(providerRedirect.headers().firstValue("Location").orElseThrow());
        Map<String, String> callbackQuery = parameters(callback.getRawQuery());
        URI invalidStateCallback = URI.create(baseUrl() + callback.getPath() + "?code="
                + encode(callbackQuery.get("code")) + "&state=not-the-issued-state");
        getAbsolute(invalidStateBrowser, invalidStateCallback.toString());
        assertThat(get(invalidStateBrowser, "/api/session").statusCode()).isEqualTo(401);

        Browser invalidCodeBrowser = browser();
        HttpResponse<String> invalidCodeAuthorization = beginAuthentication(invalidCodeBrowser);
        HttpResponse<String> invalidCodeRedirect = getAbsolute(invalidCodeBrowser,
                invalidCodeAuthorization.headers().firstValue("Location").orElseThrow() + "&mode=VALID");
        URI issuedCallback = URI.create(invalidCodeRedirect.headers().firstValue("Location").orElseThrow());
        String issuedState = parameters(issuedCallback.getRawQuery()).get("state");
        URI invalidCodeCallback = URI.create(baseUrl() + issuedCallback.getPath() + "?code=unknown-code&state="
                + encode(issuedState));
        getAbsolute(invalidCodeBrowser, invalidCodeCallback.toString());
        assertThat(get(invalidCodeBrowser, "/api/session").statusCode()).isEqualTo(401);

        assertRejectedToken(TokenMode.INVALID_ISSUER);
        assertRejectedToken(TokenMode.EXPIRED);
        assertThat(countMappings()).isEqualTo(mappingsBefore);
    }

    @Test
    void anonymousAndExpiredSessionsAreUnauthorized() throws Exception {
        assertThat(get(browser(), "/api/session").statusCode()).isEqualTo(401);

        Browser authenticated = browser();
        authenticate(authenticated, TokenMode.VALID);
        JsonNode session = objectMapper.readTree(get(authenticated, "/api/session").body());
        assertThat(post(authenticated, "/api/account/expire-session", session.path("csrfToken").asText()).statusCode())
                .isEqualTo(204);
        Thread.sleep(Duration.ofMillis(1_500));
        assertThat(get(authenticated, "/api/session").statusCode()).isEqualTo(401);
    }

    @Test
    void logoutRequiresCsrfAndInvalidatesTheSession() throws Exception {
        Browser authenticated = browser();
        authenticate(authenticated, TokenMode.VALID);
        JsonNode session = objectMapper.readTree(get(authenticated, "/api/session").body());

        assertThat(post(authenticated, "/logout", null).statusCode()).isEqualTo(403);
        assertThat(post(authenticated, "/logout", "invalid-token").statusCode()).isEqualTo(403);
        assertThat(post(authenticated, "/logout", session.path("csrfToken").asText()).statusCode()).isEqualTo(204);
        assertThat(get(authenticated, "/api/session").statusCode()).isEqualTo(401);
    }

    @Test
    void authenticatedIdentityWithoutMembershipCannotEnterATenantBoundary() throws Exception {
        Browser authenticated = browser();
        authenticate(authenticated, TokenMode.VALID);
        HttpRequest request = authenticated.request(URI.create(baseUrl() + "/api/tenant-resource"))
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .GET()
                .build();

        assertThat(authenticated.client().send(request, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);
    }

    private void assertRejectedToken(TokenMode mode) throws Exception {
        Browser browser = browser();
        authenticate(browser, mode);
        assertThat(get(browser, "/api/session").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> authenticate(Browser browser, TokenMode mode) throws Exception {
        HttpResponse<String> authorization = beginAuthentication(browser);
        String providerLocation = authorization.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> providerRedirect = getAbsolute(browser, providerLocation + "&mode=" + mode);
        return getAbsolute(browser, providerRedirect.headers().firstValue("Location").orElseThrow());
    }

    private HttpResponse<String> beginAuthentication(Browser browser) throws Exception {
        HttpResponse<String> response = get(browser, "/oauth2/authorization/dokene");
        assertThat(response.statusCode()).isEqualTo(302);
        return response;
    }

    private HttpResponse<String> get(Browser browser, String path) throws Exception {
        return getAbsolute(browser, baseUrl() + path);
    }

    private HttpResponse<String> getAbsolute(Browser browser, String location) throws Exception {
        return browser.client().send(browser.request(URI.create(location)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Browser browser, String path, String csrfToken) throws Exception {
        HttpRequest.Builder request = browser.request(URI.create(baseUrl() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return browser.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Browser browser() {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return new Browser(HttpClient.newBuilder()
                .cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), cookies);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private int countMappings() throws Exception {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM dokene.oidc_identity_mappings WHERE issuer = ? AND subject = ?")) {
            statement.setString(1, OIDC.issuer());
            statement.setString(2, SUBJECT);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static Map<String, String> parameters(String query) {
        Map<String, String> parameters = new HashMap<>();
        if (query == null || query.isBlank()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.put(decode(parts[0]), parts.length == 2 ? decode(parts[1]) : "");
        }
        return parameters;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Browser(HttpClient client, CookieManager cookies) {

        HttpRequest.Builder request(URI uri) {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri);
            String cookieHeader = cookies.getCookieStore().getCookies().stream()
                    .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
            if (!cookieHeader.isBlank() && uri.getPort() != OIDC.server.get().getAddress().getPort()) {
                request.header("Cookie", cookieHeader);
            }
            return request;
        }
    }

    private enum TokenMode {
        VALID, INVALID_ISSUER, EXPIRED
    }

    @RestController
    static final class SessionExpirationController {

        @PostMapping("/api/account/expire-session")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void expire(HttpSession session) {
            session.setMaxInactiveInterval(1);
        }
    }

    private static final class OidcStub {

        private static final String KEY_ID = "dokene-test-key";

        private final Map<String, AuthorizationCode> codes = new ConcurrentHashMap<>();
        private final AtomicReference<HttpServer> server = new AtomicReference<>();
        private RSAKey rsaKey;
        private String issuer;

        synchronized void start() throws Exception {
            if (server.get() != null) {
                return;
            }
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            rsaKey = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(KEY_ID)
                    .build();
            HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            issuer = "http://127.0.0.1:" + created.getAddress().getPort();
            created.createContext("/.well-known/openid-configuration", this::discovery);
            created.createContext("/authorize", this::authorize);
            created.createContext("/token", this::token);
            created.createContext("/jwks", this::jwks);
            created.start();
            server.set(created);
        }

        void stop() {
            HttpServer running = server.getAndSet(null);
            if (running != null) {
                running.stop(0);
            }
        }

        String issuer() {
            return issuer;
        }

        private void discovery(HttpExchange exchange) throws IOException {
            json(exchange, 200, """
                    {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
                    "jwks_uri":"%s/jwks","response_types_supported":["code"],"subject_types_supported":["public"],
                    "id_token_signing_alg_values_supported":["RS256"],"grant_types_supported":["authorization_code"],
                    "token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post"],
                    "scopes_supported":["openid","profile"]}
                    """.formatted(issuer, issuer, issuer, issuer));
        }

        private void authorize(HttpExchange exchange) throws IOException {
            Map<String, String> query = parameters(exchange.getRequestURI().getRawQuery());
            String code = UUID.randomUUID().toString();
            TokenMode mode = TokenMode.valueOf(query.getOrDefault("mode", TokenMode.VALID.name()));
            codes.put(code, new AuthorizationCode(
                    query.get("nonce"), query.get("code_challenge"), mode
            ));
            String redirect = query.get("redirect_uri") + "?code=" + encode(code) + "&state=" + encode(query.get("state"));
            exchange.getResponseHeaders().add("Location", redirect);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }

        private void token(HttpExchange exchange) throws IOException {
            Map<String, String> form = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            AuthorizationCode authorization = codes.remove(form.get("code"));
            if (authorization == null || !validVerifier(authorization.codeChallenge(), form.get("code_verifier"))) {
                json(exchange, 400, "{\"error\":\"invalid_grant\"}");
                return;
            }
            try {
                String idToken = idToken(authorization);
                json(exchange, 200, """
                        {"access_token":"server-side-access-token","token_type":"Bearer","expires_in":300,
                        "scope":"openid","id_token":"%s"}
                        """.formatted(idToken));
            } catch (Exception exception) {
                throw new IOException(exception);
            }
        }

        private void jwks(HttpExchange exchange) throws IOException {
            json(exchange, 200, "{\"keys\":[" + rsaKey.toPublicJWK() + "]}");
        }

        private String idToken(AuthorizationCode authorization) throws Exception {
            Instant now = Instant.now();
            Instant expiresAt = authorization.mode() == TokenMode.EXPIRED
                    ? now.minusSeconds(60)
                    : now.plusSeconds(300);
            String tokenIssuer = authorization.mode() == TokenMode.INVALID_ISSUER ? issuer + "/unexpected" : issuer;
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(tokenIssuer)
                    .subject(SUBJECT)
                    .audience(List.of(CLIENT_ID))
                    .issueTime(Date.from(now.minusSeconds(1)))
                    .expirationTime(Date.from(expiresAt))
                    .claim("nonce", authorization.nonce())
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(KEY_ID).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
            return jwt.serialize();
        }

        private static boolean validVerifier(String expectedChallenge, String verifier) {
            if (expectedChallenge == null) {
                return true;
            }
            if (verifier == null) {
                return false;
            }
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII));
                return expectedChallenge.equals(Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
            } catch (Exception exception) {
                return false;
            }
        }

        private static void json(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private record AuthorizationCode(String nonce, String codeChallenge, TokenMode mode) {
        }
    }
}
