package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GraphApiPropertiesTest {
    private static GraphApiProperties of(String url, String... trusted) {
        return new GraphApiProperties(URI.create(url), Duration.ofSeconds(1), List.of(trusted));
    }

    @Test
    void anEmptyBaseUrlMeansTheDefaultEndpointAsForOpenAi() {
        assertThat(of("").baseUrl()).isEqualTo(GraphApiProperties.DEFAULT_BASE_URL);
    }

    @Test
    void defaultsAreTheMetaGraphHostOverHttpsWithAShortTimeout() {
        var properties = new GraphApiProperties(null, null, null);
        assertThat(properties.baseUrl().getHost()).isEqualTo("graph.facebook.com");
        assertThat(properties.baseUrl().getScheme()).isEqualTo("https");
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.additionalTrustedHosts()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://graph.facebook.com/v23.0", "http://127.0.0.1:8080/v23.0",
            "http://localhost:9000", "https://localhost:9443", "http://[::1]:9000", "http://localhost:65535/v23.0"})
    void acceptsMetaOverHttpsAndLoopbackTestServers(String url) {
        assertThat(of(url).baseUrl()).isEqualTo(URI.create(url));
    }

    // Issue 440: the shared endpoint policy binds the canonical URL, rebuilt from the validated components, as the
    // OpenAI policy already did: lower-case scheme and host, dot segments removed, repeated slashes in the path
    // collapsed by URI.normalize() and a default port dropped.
    @ParameterizedTest
    @CsvSource({
            "https://GRAPH.FACEBOOK.COM/v23.0, https://graph.facebook.com/v23.0",
            "HTTPS://graph.facebook.com/v23.0, https://graph.facebook.com/v23.0",
            "https://graph.facebook.com:443/v23.0, https://graph.facebook.com/v23.0",
            "https://graph.facebook.com/v23.0/./x/../, https://graph.facebook.com/v23.0/",
            "https://graph.facebook.com//v23.0, https://graph.facebook.com/v23.0",
            "https://graph.facebook.com/v23.0//x, https://graph.facebook.com/v23.0/x",
            "http://localhost:80/v23.0, http://localhost/v23.0",
            "https://localhost:80/v23.0, https://localhost:80/v23.0"})
    void bindsTheCanonicalUrl(String configured, String canonical) {
        assertThat(of(configured).baseUrl()).isEqualTo(URI.create(canonical));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "https://example.com/v23.0", "https://graph.facebook.com.evil.example",
            "https://evilgraph.facebook.com", "https://sub.graph.facebook.com", "https://facebook.com",
            "https://graph.facebook.com@evil.example", "https://graph.facebook.com:443@evil.example/x",
            "http://graph.facebook.com/v23.0", "http://10.0.0.5", "http://example.com:8080",
            "ftp://graph.facebook.com", "https://user:secret@graph.facebook.com", "https://graph.facebook.com?x=1",
            "https://graph.facebook.com#frag", "https:///path", "http://localhost.evil.com",
            "https://graph.facebook.com.", "https://10.0.0.5",
            // Issue 428 (audit I3): a name under localhost reaches loopback only if the resolver says so, and other
            // spellings of a loopback address are not the exact rule either.
            "http://stub.localhost/v23.0", "http://localhost./v23.0", "http://127.0.0.2", "http://[0:0:0:0:0:0:0:1]",
            // Issue 440: the OpenAI rule, now shared. Port 0 and ports above 65535 are refused, and so are empty
            // query, fragment and user info, and an opaque or host-less value.
            "http://localhost:0/v23.0", "https://graph.facebook.com:0/v23.0", "http://localhost:65536/v23.0",
            "https://graph.facebook.com:70000/v23.0", "https://graph.facebook.com/v23.0?",
            "https://graph.facebook.com/v23.0#", "https://@graph.facebook.com/v23.0",
            "https://evil.example#@graph.facebook.com/v23.0", "https://graph%2efacebook%2ecom/v23.0",
            "mailto:a@graph.facebook.com", "graph.facebook.com/v23.0", "/v23.0",
            // A path that still climbs above the root after normalization has no canonical form.
            "https://graph.facebook.com/../v23.0", "https://graph.facebook.com/..",
            "http://localhost:9000/a/../../v23.0"})
    void rejectsEveryOtherHostSoTenantCredentialsCannotLeak(String url) {
        assertThatThrownBy(() -> of(url)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("dokene.integration.whatsapp.graph.base-url must be https on graph.facebook.com");
    }

    @Test
    void theRejectionNeverEchoesTheConfiguredValue() {
        assertThatThrownBy(() -> of("https://user:s3cr3t-token@evil.example/v23.0?key=s3cr3t-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("s3cr3t-token").doesNotContain("evil.example");
    }

    @Test
    void aTrustedProxyIsAcceptedOnlyWhenExplicitlyListedAndOverHttps() {
        assertThat(of("https://graph-proxy.internal.example/v23.0", "graph-proxy.internal.example").baseUrl().getHost())
                .isEqualTo("graph-proxy.internal.example");
        assertThatThrownBy(() -> of("https://graph-proxy.internal.example", "other.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("http://graph-proxy.internal.example", "graph-proxy.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
        // A listed host does not widen the match to its subdomains or parents.
        assertThatThrownBy(() -> of("https://a.graph-proxy.internal.example", "graph-proxy.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://proxy.example", "proxy.example:8443", "*.example.com", "Proxy.Example",
            "proxy.example/path", "", " ", "proxy..example", "-proxy.example", "10.0.0.5.", "10.0.0.5",
            "169.254.169.254", "127.1"})
    void rejectsMalformedTrustedHostEntries(String entry) {
        assertThatThrownBy(() -> of("https://graph.facebook.com", entry)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dokene.integration.whatsapp.graph.additional-trusted-hosts entries must be exact "
                        + "lower-case host names");
    }

    @Test
    void rejectsANonPositiveTimeout() {
        assertThatThrownBy(() -> new GraphApiProperties(null, Duration.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration
    @EnableConfigurationProperties(GraphApiProperties.class)
    static class PropertiesOnly {
    }

    @Test
    void aRejectedConfigurationStopsTheApplicationBeforeAnyCredentialedRequestCanExist() {
        var runner = new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class);
        runner.withPropertyValues("dokene.integration.whatsapp.graph.base-url=https://example.com/v23.0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("dokene.integration.whatsapp.graph.base-url=https://graph.facebook.com.evil.example")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("dokene.integration.whatsapp.graph.base-url=https://graph.facebook.com/v23.0")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("dokene.integration.whatsapp.graph.base-url=")
                .run(context -> assertThat(context.getBean(GraphApiProperties.class).baseUrl())
                        .isEqualTo(GraphApiProperties.DEFAULT_BASE_URL));
        runner.withPropertyValues("dokene.integration.whatsapp.graph.base-url=https://proxy.internal.example",
                "dokene.integration.whatsapp.graph.additional-trusted-hosts=proxy.internal.example")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
