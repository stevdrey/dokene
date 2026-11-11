// ABOUTME: A cookie keeping HTTP client that never follows redirects, for driving the OIDC login of the BFF chain
// ABOUTME: step by step against OidcProviderStub in the session tests (issue 311).
package io.github.stevdrey.dokene.identity.security;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.stream.Collectors;

final class TestBrowser {

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(cookies)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final String baseUrl;

    TestBrowser(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
    }

    /** {@code pathOrUrl} is a path on the application, or an absolute URL such as a redirect target. */
    HttpResponse<String> get(String pathOrUrl) throws Exception {
        return send(pathOrUrl, "GET");
    }

    /** An unsafe request with an empty body and no CSRF token. */
    HttpResponse<String> postWithoutCsrfToken(String pathOrUrl) throws Exception {
        return send(pathOrUrl, "POST");
    }

    private HttpResponse<String> send(String pathOrUrl, String method) throws Exception {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl + pathOrUrl;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .method(method, HttpRequest.BodyPublishers.noBody());
        // The session cookie is Secure by default, so the cookie handler stores it but will not send it over the
        // plain HTTP of the test server. Only the application gets it, never the provider stub.
        String cookieHeader = cookies.getCookieStore().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
        if (url.startsWith(baseUrl) && !cookieHeader.isEmpty()) {
            request.header("Cookie", cookieHeader);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The JSESSIONID the browser currently holds. */
    Optional<String> sessionId() {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "JSESSIONID".equalsIgnoreCase(cookie.getName()))
                .map(cookie -> cookie.getValue())
                .findFirst();
    }

    /** Starts a login and returns the provider URL the application redirects to. */
    String startLogin() throws Exception {
        HttpResponse<String> response = get("/oauth2/authorization/dokene");
        if (response.statusCode() != 302) {
            throw new IllegalStateException("Login start answered " + response.statusCode());
        }
        return response.headers().firstValue("Location").orElseThrow();
    }

    /** Visits the provider and returns the callback URL it redirects to, without calling it. */
    String callbackUrlFrom(String providerUrl) throws Exception {
        return get(providerUrl).headers().firstValue("Location").orElseThrow();
    }

    /** Runs a whole login and returns the application's answer to the callback. */
    HttpResponse<String> login() throws Exception {
        return get(callbackUrlFrom(startLogin()));
    }
}
