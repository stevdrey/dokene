package io.github.stevdrey.dokene.identity.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
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
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
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
import java.sql.ResultSet;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end security test suite verifying BFF session authentication, CSRF enforcement,
 * and multi-tenant boundary isolation against real PostgreSQL Row Level Security.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BffTenantBoundarySecurityIntegrationTest {

    private static final String CLIENT_ID = "dokene-boundary-test";
    private static final String CLIENT_SECRET = "boundary-secret";
    private static final MultiSubjectOidcStub OIDC = new MultiSubjectOidcStub();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("dokene.provisioning.enabled", () -> "true");
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

    @Autowired
    private TenantRepository tenants;

    @Autowired
    private TenantMembershipRepository memberships;

    @Autowired
    private TenantContextProvider contexts;

    @Autowired
    private DatabaseContextSigner signer;

    @Test
    void authenticatedIdentityWithoutMembershipFailsClosedOnTenantApis() throws Exception {
        Browser charlie = browser();
        authenticate(charlie, "user-charlie");

        HttpResponse<String> sessionResponse = get(charlie, "/api/session");
        assertThat(sessionResponse.statusCode()).isEqualTo(200);
        JsonNode session = objectMapper.readTree(sessionResponse.body());
        assertThat(session.path("authenticated").asBoolean()).isTrue();
        String csrf = session.path("csrfToken").asText();

        // Charlie has no memberships
        HttpResponse<String> tenantsResponse = get(charlie, "/api/tenants");
        assertThat(tenantsResponse.statusCode()).isEqualTo(200);
        JsonNode tenantsList = objectMapper.readTree(tenantsResponse.body());
        assertThat(tenantsList.isArray()).isTrue();
        assertThat(tenantsList.isEmpty()).isTrue();

        // Attempting to access tenant-scoped resource with an arbitrary/forged tenant id fails closed
        UUID unassociatedTenantId = UUID.randomUUID();
        HttpResponse<String> getCustomers = getWithTenant(charlie, "/api/customers", unassociatedTenantId);
        assertThat(getCustomers.statusCode()).isEqualTo(403);

        HttpResponse<String> postCustomer = postWithTenant(charlie, "/api/customers", unassociatedTenantId, csrf,
                """
                {"displayName":"Unauthed Customer","phones":[{"number":"88887777","region":"CR","primary":true}]}
                """);
        assertThat(postCustomer.statusCode()).isEqualTo(403);
    }

    @Test
    void crossTenantIsolationEnforcedBetweenAuthenticatedIdentities() throws Exception {
        Instant now = Instant.now();

        // 1. Authenticate Alice and seed Tenant 1 (Alice is OWNER)
        Browser alice = browser();
        authenticate(alice, "user-alice");
        JsonNode aliceSession = objectMapper.readTree(get(alice, "/api/session").body());
        IdentityId aliceIdentity = new IdentityId(UUID.fromString(aliceSession.path("identityId").asText()));
        String aliceCsrf = aliceSession.path("csrfToken").asText();

        Tenant tenant1 = seedTenant(tenants, "Tenant Alpha " + UUID.randomUUID(), now);
        seedMembership(memberships, contexts, tenant1.id(), aliceIdentity, TenantRole.OWNER, now);

        // 2. Authenticate Bob and seed Tenant 2 (Bob is OWNER)
        Browser bob = browser();
        authenticate(bob, "user-bob");
        JsonNode bobSession = objectMapper.readTree(get(bob, "/api/session").body());
        IdentityId bobIdentity = new IdentityId(UUID.fromString(bobSession.path("identityId").asText()));
        String bobCsrf = bobSession.path("csrfToken").asText();

        Tenant tenant2 = seedTenant(tenants, "Tenant Beta " + UUID.randomUUID(), now);
        seedMembership(memberships, contexts, tenant2.id(), bobIdentity, TenantRole.OWNER, now);

        // 3. Alice creates a customer in Tenant 1 with valid session and CSRF
        HttpResponse<String> createCustomerResponse = postWithTenant(alice, "/api/customers", tenant1.id().value(),
                aliceCsrf,
                """
                {"displayName":"Alice Secret Customer","phones":[{"number":"88887777","region":"CR","primary":true}]}
                """);
        assertThat(createCustomerResponse.statusCode()).isEqualTo(201);
        JsonNode createdCustomer = objectMapper.readTree(createCustomerResponse.body());
        UUID aliceCustomerId = UUID.fromString(createdCustomer.path("id").asText());

        // Alice can read her customer
        HttpResponse<String> aliceRead = getWithTenant(alice, "/api/customers/" + aliceCustomerId, tenant1.id().value());
        assertThat(aliceRead.statusCode()).isEqualTo(200);

        // 4. Bob attempts to access Alice's customer under Bob's tenant (guessed UUID)
        // -> 404 at application layer (enforced by JdbcCustomerRepository WHERE tenant_id = ? AND id = ?)
        HttpResponse<String> bobGuessedRead = getWithTenant(bob, "/api/customers/" + aliceCustomerId, tenant2.id().value());
        assertThat(bobGuessedRead.statusCode()).isEqualTo(404);

        // Exercise PostgreSQL Row Level Security independently of the application repository WHERE predicate:
        // Under Bob's signed database context, direct runtime SQL selecting Alice's ID without tenant_id predicate
        // returns empty because the PostgreSQL RLS policy filters it out.
        try (Connection bobConnection = TenantSecurityIntegrationFixture.runtimeConnection(signer.issueTenantContext(tenant2.id()));
                PreparedStatement selectStmt = bobConnection.prepareStatement("SELECT id FROM dokene.customers WHERE id = ?");
                PreparedStatement updateStmt = bobConnection.prepareStatement("UPDATE dokene.customers SET display_name = 'Hacked' WHERE id = ?")) {
            selectStmt.setObject(1, aliceCustomerId);
            try (ResultSet rs = selectStmt.executeQuery()) {
                assertThat(rs.next()).isFalse();
            }
            updateStmt.setObject(1, aliceCustomerId);
            assertThat(updateStmt.executeUpdate()).isZero();
        }

        // 5. Bob attempts to access Tenant 1 by forging X-Tenant-Id -> 403 Forbidden (membership verification fails)
        HttpResponse<String> bobForgedRead = getWithTenant(bob, "/api/customers/" + aliceCustomerId, tenant1.id().value());
        assertThat(bobForgedRead.statusCode()).isEqualTo(403);

        HttpResponse<String> bobForgedList = getWithTenant(bob, "/api/customers", tenant1.id().value());
        assertThat(bobForgedList.statusCode()).isEqualTo(403);

        // 6. Bob attempts to mutate Alice's customer with forged tenant -> 403 Forbidden
        HttpResponse<String> bobForgedMutate = postWithTenant(bob, "/api/customers", tenant1.id().value(), bobCsrf,
                """
                {"displayName":"Attacker Customer","phones":[{"number":"88880000","region":"CR","primary":true}]}
                """);
        assertThat(bobForgedMutate.statusCode()).isEqualTo(403);

        // 7. Replaying Alice's session cookie remains strictly bound to Alice's identity;
        // possession of Alice's bearer cookie acts as Alice and does not adopt or rebind to Bob's identity
        String aliceSessionId = sessionId(alice);
        HttpRequest replaySessionRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/session"))
                .header("Cookie", "JSESSIONID=" + aliceSessionId)
                .GET()
                .build();
        HttpResponse<String> sessionEcho = HttpClient.newHttpClient().send(replaySessionRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(sessionEcho.statusCode()).isEqualTo(200);
        // Echoed session remains Alice's identity, never Bob's
        assertThat(objectMapper.readTree(sessionEcho.body()).path("identityId").asText())
                .isEqualTo(aliceIdentity.value().toString());
    }

    @Test
    void workspaceSwitchingWithinSameSessionPreservesStrictIsolation() throws Exception {
        Instant now = Instant.now();

        // 1. Authenticate Alice
        Browser alice = browser();
        authenticate(alice, "user-alice-multi");
        JsonNode aliceSession = objectMapper.readTree(get(alice, "/api/session").body());
        IdentityId aliceIdentity = new IdentityId(UUID.fromString(aliceSession.path("identityId").asText()));
        String aliceCsrf = aliceSession.path("csrfToken").asText();

        // 2. Grant Alice active OWNER memberships in two separate tenants
        Tenant tenantA = seedTenant(tenants, "Workspace A " + UUID.randomUUID(), now);
        seedMembership(memberships, contexts, tenantA.id(), aliceIdentity, TenantRole.OWNER, now);

        Tenant tenantB = seedTenant(tenants, "Workspace B " + UUID.randomUUID(), now);
        seedMembership(memberships, contexts, tenantB.id(), aliceIdentity, TenantRole.OWNER, now);

        // 3. Create distinct customer in Tenant A
        HttpResponse<String> createInA = postWithTenant(alice, "/api/customers", tenantA.id().value(), aliceCsrf,
                """
                {"displayName":"Customer in Workspace A","phones":[{"number":"88881111","region":"CR","primary":true}]}
                """);
        assertThat(createInA.statusCode()).isEqualTo(201);
        UUID customerAId = UUID.fromString(objectMapper.readTree(createInA.body()).path("id").asText());

        // 4. Create distinct customer in Tenant B
        HttpResponse<String> createInB = postWithTenant(alice, "/api/customers", tenantB.id().value(), aliceCsrf,
                """
                {"displayName":"Customer in Workspace B","phones":[{"number":"88882222","region":"CR","primary":true}]}
                """);
        assertThat(createInB.statusCode()).isEqualTo(201);
        UUID customerBId = UUID.fromString(objectMapper.readTree(createInB.body()).path("id").asText());

        // 5. Sequential workspace switching requests in the same session
        HttpResponse<String> listA1 = getWithTenant(alice, "/api/customers", tenantA.id().value());
        assertThat(listA1.statusCode()).isEqualTo(200);
        JsonNode customersA1 = objectMapper.readTree(listA1.body()).path("customers");
        assertThat(customersA1).extracting(node -> node.path("id").asText()).contains(customerAId.toString());
        assertThat(customersA1).extracting(node -> node.path("id").asText()).doesNotContain(customerBId.toString());

        HttpResponse<String> listB = getWithTenant(alice, "/api/customers", tenantB.id().value());
        assertThat(listB.statusCode()).isEqualTo(200);
        JsonNode customersB = objectMapper.readTree(listB.body()).path("customers");
        assertThat(customersB).extracting(node -> node.path("id").asText()).contains(customerBId.toString());
        assertThat(customersB).extracting(node -> node.path("id").asText()).doesNotContain(customerAId.toString());

        HttpResponse<String> listA2 = getWithTenant(alice, "/api/customers", tenantA.id().value());
        assertThat(listA2.statusCode()).isEqualTo(200);
        JsonNode customersA2 = objectMapper.readTree(listA2.body()).path("customers");
        assertThat(customersA2).extracting(node -> node.path("id").asText()).contains(customerAId.toString());
        assertThat(customersA2).extracting(node -> node.path("id").asText()).doesNotContain(customerBId.toString());
    }

    @Test
    void csrfEnforcementOnRepresentativeBusinessMutations() throws Exception {
        Instant now = Instant.now();

        Browser alice = browser();
        authenticate(alice, "user-alice-csrf");
        JsonNode aliceSession = objectMapper.readTree(get(alice, "/api/session").body());
        IdentityId aliceIdentity = new IdentityId(UUID.fromString(aliceSession.path("identityId").asText()));
        String aliceCsrf = aliceSession.path("csrfToken").asText();

        Tenant tenant = seedTenant(tenants, "CSRF Test Workspace " + UUID.randomUUID(), now);
        seedMembership(memberships, contexts, tenant.id(), aliceIdentity, TenantRole.OWNER, now);

        String customerPayload = """
                {"displayName":"CSRF Customer","phones":[{"number":"88883333","region":"CR","primary":true}]}
                """;

        // 1. Missing CSRF -> 403 Forbidden
        HttpResponse<String> missingCsrf = postWithTenant(alice, "/api/customers", tenant.id().value(), null, customerPayload);
        assertThat(missingCsrf.statusCode()).isEqualTo(403);

        // 2. Invalid CSRF -> 403 Forbidden
        HttpResponse<String> invalidCsrf = postWithTenant(alice, "/api/customers", tenant.id().value(), "forged-csrf-token", customerPayload);
        assertThat(invalidCsrf.statusCode()).isEqualTo(403);

        // 3. Valid CSRF -> 201 Created
        HttpResponse<String> validCsrf = postWithTenant(alice, "/api/customers", tenant.id().value(), aliceCsrf, customerPayload);
        assertThat(validCsrf.statusCode()).isEqualTo(201);

        // 4. Provisioning mutation (POST /api/tenants) with CSRF
        HttpRequest.Builder provisionWithoutCsrf = alice.request(URI.create(baseUrl() + "/api/tenants"))
                .header("Idempotency-Key", "provision-" + UUID.randomUUID())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"New Workspace CSRF\"}"));
        assertThat(alice.client().send(provisionWithoutCsrf.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(403);

        HttpRequest.Builder provisionWithCsrf = alice.request(URI.create(baseUrl() + "/api/tenants"))
                .header("Idempotency-Key", "provision-" + UUID.randomUUID())
                .header("X-CSRF-TOKEN", aliceCsrf)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"displayName\":\"New Workspace CSRF\"}"));
        HttpResponse<String> provisionResponse = alice.client().send(provisionWithCsrf.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(provisionResponse.statusCode()).isEqualTo(201);
        JsonNode provisioned = objectMapper.readTree(provisionResponse.body());
        assertThat(provisioned.path("displayName").asText()).isEqualTo("New Workspace CSRF");
        assertThat(provisioned.path("role").asText()).isEqualTo("OWNER");
    }

    @Test
    void failClosed401Vs403BehaviorAcrossEndpoints() throws Exception {
        Instant now = Instant.now();

        // 1. Anonymous requests fail closed with 401
        Browser anon = browser();
        assertThat(get(anon, "/api/session").statusCode()).isEqualTo(401);
        assertThat(get(anon, "/api/tenants").statusCode()).isEqualTo(401);
        assertThat(getWithTenant(anon, "/api/customers", UUID.randomUUID()).statusCode()).isEqualTo(401);

        // 2. Tampered session cookie fails closed with 401
        HttpRequest tamperedRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/session"))
                .header("Cookie", "JSESSIONID=tampered-session-cookie-xyz")
                .GET()
                .build();
        assertThat(HttpClient.newHttpClient().send(tamperedRequest, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);

        // 3. Authenticated Dave with VIEWER role in Tenant 1
        Browser dave = browser();
        authenticate(dave, "user-dave-viewer");
        JsonNode daveSession = objectMapper.readTree(get(dave, "/api/session").body());
        IdentityId daveIdentity = new IdentityId(UUID.fromString(daveSession.path("identityId").asText()));
        String daveCsrf = daveSession.path("csrfToken").asText();

        Tenant tenant = seedTenant(tenants, "Permission Test " + UUID.randomUUID(), now);
        seedMembership(memberships, contexts, tenant.id(), daveIdentity, TenantRole.VIEWER, now);

        // Dave can read customers (VIEWER permission)
        HttpResponse<String> daveRead = getWithTenant(dave, "/api/customers", tenant.id().value());
        assertThat(daveRead.statusCode()).isEqualTo(200);

        // Dave cannot create customer (CUSTOMER_WRITE required, VIEWER forbidden) -> 403 Forbidden
        HttpResponse<String> daveWrite = postWithTenant(dave, "/api/customers", tenant.id().value(), daveCsrf,
                """
                {"displayName":"Viewer Write Denied","phones":[{"number":"88884444","region":"CR","primary":true}]}
                """);
        assertThat(daveWrite.statusCode()).isEqualTo(403);
    }

    @Test
    void sessionInvalidationAndPostLogoutReuseRejected() throws Exception {
        Browser user = browser();
        authenticate(user, "user-logout-test");
        JsonNode session = objectMapper.readTree(get(user, "/api/session").body());
        String csrf = session.path("csrfToken").asText();
        String activeSessionId = sessionId(user);

        // Logout requires CSRF and returns 204
        HttpResponse<String> logoutResponse = post(user, "/logout", csrf);
        assertThat(logoutResponse.statusCode()).isEqualTo(204);

        // Verify that the browser is instructed to delete JSESSIONID cookie
        List<String> setCookies = logoutResponse.headers().allValues("Set-Cookie");
        assertThat(setCookies).anySatisfy(cookie -> {
            assertThat(cookie).containsIgnoringCase("JSESSIONID=");
            assertThat(cookie).satisfiesAnyOf(
                    c -> assertThat(c).containsIgnoringCase("Max-Age=0"),
                    c -> assertThat(c).containsIgnoringCase("Expires=")
            );
        });

        // Reusing the session immediately fails closed with 401
        assertThat(get(user, "/api/session").statusCode()).isEqualTo(401);

        HttpRequest rawReplay = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/session"))
                .header("Cookie", "JSESSIONID=" + activeSessionId)
                .GET()
                .build();
        assertThat(HttpClient.newHttpClient().send(rawReplay, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(401);
    }

    @Test
    void staleOrFailedOidcCallbackRedirectsToFrontendFailureUrlWithoutExposingInternalPages() throws Exception {
        Browser browser = browser();

        // Stale or invalid callback request
        HttpResponse<String> callbackResponse = get(browser, "/login/oauth2/code/dokene?code=stale_code_xyz&state=stale_state_xyz");
        assertThat(callbackResponse.statusCode()).isEqualTo(302);

        String redirectTarget = callbackResponse.headers().firstValue("Location").orElseThrow();
        // Redirect target must point to frontend error parameter rather than internal /login?error
        assertThat(redirectTarget).doesNotContain("/login?error");
        assertThat(redirectTarget).endsWith("/?error=login_failed");

        // Assert sensitive tokens and provider secrets are strictly absent
        assertThat(redirectTarget).doesNotContain("access_token", "id_token", "refresh_token", CLIENT_SECRET);
        assertThat(callbackResponse.headers().map().toString()).doesNotContain(CLIENT_SECRET, "server-side-access-token");

        // Stale/failed callback must not establish an active session
        HttpResponse<String> sessionResponse = get(browser, "/api/session");
        assertThat(sessionResponse.statusCode()).isEqualTo(401);

        // Accessing /login or /login?error directly must not render Spring Security's default unstyled login page
        HttpResponse<String> loginPageResponse = get(browser, "/login?error");
        assertThat(loginPageResponse.body()).doesNotContain("Login with OAuth 2.0");
        assertThat(loginPageResponse.body()).doesNotContain("Please sign in");
        assertThat(loginPageResponse.body()).doesNotContain(OIDC.issuer());
    }

    private static String sessionId(Browser browser) {
        return browser.cookies().getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equalsIgnoreCase(cookie.getName()))
                .map(HttpCookie::getValue)
                .findFirst()
                .orElseThrow();
    }

    private HttpResponse<String> authenticate(Browser browser, String subject) throws Exception {
        HttpResponse<String> authorization = beginAuthentication(browser);
        String providerLocation = authorization.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> providerRedirect = getAbsolute(browser, providerLocation + "&sub=" + encode(subject));
        String callbackLocation = providerRedirect.headers().firstValue("Location").orElseThrow();
        HttpResponse<String> callbackResponse = getAbsolute(browser, callbackLocation);

        // Inspect callback redirect URL and response headers for token isolation
        String redirectTarget = callbackResponse.headers().firstValue("Location").orElse("");
        assertThat(redirectTarget).doesNotContain("access_token", "id_token", "refresh_token", CLIENT_SECRET, "server-side-access-token");
        assertThat(callbackResponse.headers().map().toString()).doesNotContain(CLIENT_SECRET, "server-side-access-token");

        return callbackResponse;
    }

    private HttpResponse<String> beginAuthentication(Browser browser) throws Exception {
        HttpResponse<String> response = get(browser, "/oauth2/authorization/dokene");
        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location)
                .contains("code_challenge=")
                .contains("code_challenge_method=S256");
        return response;
    }

    private HttpResponse<String> get(Browser browser, String path) throws Exception {
        return getAbsolute(browser, baseUrl() + path);
    }

    private HttpResponse<String> getAbsolute(Browser browser, String location) throws Exception {
        return browser.client().send(browser.request(URI.create(location)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithTenant(Browser browser, String path, UUID tenantId) throws Exception {
        HttpRequest request = browser.request(URI.create(baseUrl() + path))
                .header("X-Tenant-Id", tenantId.toString())
                .GET()
                .build();
        return browser.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(Browser browser, String path, String csrfToken) throws Exception {
        HttpRequest.Builder request = browser.request(URI.create(baseUrl() + path))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return browser.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postWithTenant(Browser browser, String path, UUID tenantId, String csrfToken, String body)
            throws Exception {
        HttpRequest.Builder request = browser.request(URI.create(baseUrl() + path))
                .header("X-Tenant-Id", tenantId.toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
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

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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

    private static final class MultiSubjectOidcStub {

        private static final String KEY_ID = "dokene-boundary-test-key";

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
            created.createContext("/logout", this::logout);
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
                    "end_session_endpoint":"%s/logout",
                    "jwks_uri":"%s/jwks","response_types_supported":["code"],"subject_types_supported":["public"],
                    "id_token_signing_alg_values_supported":["RS256"],"grant_types_supported":["authorization_code"],
                    "token_endpoint_auth_methods_supported":["client_secret_basic","client_secret_post"],
                    "scopes_supported":["openid","profile"]}
                    """.formatted(issuer, issuer, issuer, issuer, issuer));
        }

        private void logout(HttpExchange exchange) throws IOException {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        }

        private void authorize(HttpExchange exchange) throws IOException {
            Map<String, String> query = parameters(exchange.getRequestURI().getRawQuery());
            String challenge = query.get("code_challenge");
            String challengeMethod = query.get("code_challenge_method");
            if (challenge == null || challenge.isBlank() || !"S256".equals(challengeMethod)) {
                json(exchange, 400, "{\"error\":\"invalid_request\",\"error_description\":\"PKCE S256 code_challenge required\"}");
                return;
            }
            String code = UUID.randomUUID().toString();
            String subject = query.getOrDefault("sub", "default-user");
            codes.put(code, new AuthorizationCode(
                    query.get("nonce"), challenge, subject
            ));
            String redirect = query.get("redirect_uri") + "?code=" + encode(code) + "&state=" + encode(query.get("state"));
            exchange.getResponseHeaders().add("Location", redirect);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        }

        private void token(HttpExchange exchange) throws IOException {
            Map<String, String> form = parameters(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            AuthorizationCode authorization = codes.remove(form.get("code"));
            String verifier = form.get("code_verifier");
            if (authorization == null || verifier == null || verifier.isBlank() || !validVerifier(authorization.codeChallenge(), verifier)) {
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
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(authorization.subject())
                    .audience(List.of(CLIENT_ID))
                    .issueTime(Date.from(now.minusSeconds(1)))
                    .expirationTime(Date.from(now.plusSeconds(300)))
                    .claim("nonce", authorization.nonce())
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(KEY_ID).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(rsaKey.toRSAPrivateKey()));
            return jwt.serialize();
        }

        private static boolean validVerifier(String expectedChallenge, String verifier) {
            if (expectedChallenge == null || expectedChallenge.isBlank() || verifier == null || verifier.isBlank()) {
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

        private record AuthorizationCode(String nonce, String codeChallenge, String subject) {
        }
    }
}
