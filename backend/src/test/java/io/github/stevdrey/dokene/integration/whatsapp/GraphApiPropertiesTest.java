package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class GraphApiPropertiesTest {
    private static GraphApiProperties of(String url, String... trusted) {
        return new GraphApiProperties(URI.create(url), Duration.ofSeconds(1), List.of(trusted));
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
    @ValueSource(strings = {"https://graph.facebook.com/v23.0", "https://GRAPH.FACEBOOK.COM/v23.0",
            "http://127.0.0.1:8080/v23.0", "http://localhost:9000", "https://localhost:9443",
            "http://[::1]:9000", "http://stub.localhost/v23.0"})
    void acceptsMetaOverHttpsAndLoopbackTestServers(String url) {
        assertThat(of(url).baseUrl()).isEqualTo(URI.create(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://example.com", "https://example.com/v23.0", "https://graph.facebook.com.evil.example",
            "https://evilgraph.facebook.com", "https://sub.graph.facebook.com", "https://facebook.com",
            "https://graph.facebook.com@evil.example", "https://graph.facebook.com:443@evil.example/x",
            "http://graph.facebook.com/v23.0", "http://10.0.0.5", "http://example.com:8080",
            "ftp://graph.facebook.com", "https://user:secret@graph.facebook.com", "https://graph.facebook.com?x=1",
            "https://graph.facebook.com#frag", "https:///path", "http://localhost.evil.com",
            "https://graph.facebook.com.", "https://10.0.0.5"})
    void rejectsEveryOtherHostSoTenantCredentialsCannotLeak(String url) {
        assertThatThrownBy(() -> of(url)).isInstanceOf(IllegalArgumentException.class);
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
            "proxy.example/path", "", " ", "proxy..example", "-proxy.example", "10.0.0.5."})
    void rejectsMalformedTrustedHostEntries(String entry) {
        assertThatThrownBy(() -> of("https://graph.facebook.com", entry)).isInstanceOf(IllegalArgumentException.class);
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
        runner.withPropertyValues("dokene.integration.whatsapp.graph.base-url=https://proxy.internal.example",
                "dokene.integration.whatsapp.graph.additional-trusted-hosts=proxy.internal.example")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
