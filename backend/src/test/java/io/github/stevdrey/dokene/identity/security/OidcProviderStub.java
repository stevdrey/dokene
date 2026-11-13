// ABOUTME: In-process OpenID Connect provider for the anonymous and pre login session tests (issue 311).
// ABOUTME: Serves discovery, authorization, token and JWKS endpoints on loopback and registers itself as "dokene".
package io.github.stevdrey.dokene.identity.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.test.context.DynamicPropertyRegistry;

/** One provider for the whole test JVM, so test classes with the same settings share one application context. */
final class OidcProviderStub {

    static final String CLIENT_ID = "dokene-session-test";
    /** Synthetic value, never a real client secret. */
    static final String CLIENT_SECRET = "synthetic-test-secret-0311";

    private static final String KEY_ID = "dokene-session-test-key";
    private static final String SUBJECT = "pre-login-session-user";
    private static final OidcProviderStub SHARED = new OidcProviderStub();

    private final Map<String, String> noncesByCode = new ConcurrentHashMap<>();
    private HttpServer server;
    private RSAKey rsaKey;
    private String issuer;

    private OidcProviderStub() {
    }

    /** Starts the shared provider if needed and points the "dokene" client registration at it. */
    static OidcProviderStub register(DynamicPropertyRegistry registry) throws Exception {
        SHARED.start();
        registry.add("spring.security.oauth2.client.registration.dokene.provider", () -> "dokene");
        registry.add("spring.security.oauth2.client.registration.dokene.client-id", () -> CLIENT_ID);
        registry.add("spring.security.oauth2.client.registration.dokene.client-secret", () -> CLIENT_SECRET);
        registry.add("spring.security.oauth2.client.registration.dokene.scope", () -> "openid");
        registry.add("spring.security.oauth2.client.registration.dokene.authorization-grant-type",
                () -> "authorization_code");
        registry.add("spring.security.oauth2.client.registration.dokene.redirect-uri",
                () -> "{baseUrl}/login/oauth2/code/{registrationId}");
        registry.add("spring.security.oauth2.client.provider.dokene.issuer-uri", SHARED::issuer);
        return SHARED;
    }

    String issuer() {
        return issuer;
    }

    int port() {
        return server.getAddress().getPort();
    }

    private synchronized void start() throws Exception {
        if (server != null) {
            return;
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
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
        server = created;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> created.stop(0)));
    }

    private void discovery(HttpExchange exchange) throws IOException {
        json(exchange, 200, """
                {"issuer":"%s","authorization_endpoint":"%s/authorize","token_endpoint":"%s/token",
                "jwks_uri":"%s/jwks","response_types_supported":["code"],"subject_types_supported":["public"],
                "id_token_signing_alg_values_supported":["RS256"],
                "grant_types_supported":["authorization_code","refresh_token"],
                "backchannel_logout_supported":true,"backchannel_logout_session_supported":true,
                "token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post"],
                "scopes_supported":["openid","profile"]}
                """.formatted(issuer, issuer, issuer, issuer));
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> query = parameters(exchange.getRequestURI().getRawQuery());
        String code = UUID.randomUUID().toString();
        noncesByCode.put(code, query.getOrDefault("nonce", ""));
        exchange.getResponseHeaders().add("Location", query.get("redirect_uri") + "?code=" + encode(code)
                + "&state=" + encode(query.get("state")));
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        Map<String, String> form = parameters(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String nonce = noncesByCode.remove(form.getOrDefault("code", ""));
        if (nonce == null) {
            json(exchange, 400, "{\"error\":\"invalid_grant\"}");
            return;
        }
        String sid = UUID.randomUUID().toString();
        try {
            json(exchange, 200, """
                    {"access_token":"synthetic-access-token","token_type":"Bearer","expires_in":300,
                    "refresh_token":"refresh-%s","scope":"openid","id_token":"%s"}
                    """.formatted(sid, idToken(nonce, sid)));
        } catch (Exception exception) {
            throw new IOException(exception);
        }
    }

    private void jwks(HttpExchange exchange) throws IOException {
        json(exchange, 200, "{\"keys\":[" + rsaKey.toPublicJWK() + "]}");
    }

    private String idToken(String nonce, String sid) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(SUBJECT)
                .audience(List.of(CLIENT_ID))
                .issueTime(Date.from(now.minusSeconds(1)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce)
                .claim("sid", sid)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(KEY_ID).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
        return jwt.serialize();
    }

    static Map<String, String> parameters(String query) {
        Map<String, String> parameters = new HashMap<>();
        if (query == null || query.isBlank()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            parameters.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return parameters;
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
