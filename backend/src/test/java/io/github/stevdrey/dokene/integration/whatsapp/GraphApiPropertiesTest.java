package io.github.stevdrey.dokene.integration.whatsapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GraphApiPropertiesTest {
    @Test
    void defaultsAreHttpsWithAShortTimeout() {
        var properties = new GraphApiProperties(null, null);
        assertThat(properties.baseUrl().getScheme()).isEqualTo("https");
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://graph.facebook.com/v23.0", "http://127.0.0.1:8080/v23.0",
            "http://localhost:9000", "http://[::1]:9000", "http://stub.localhost/v23.0"})
    void acceptsHttpsAndLoopbackHttp(String url) {
        assertThat(new GraphApiProperties(URI.create(url), Duration.ofSeconds(1)).baseUrl()).isEqualTo(URI.create(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://graph.facebook.com/v23.0", "http://10.0.0.5", "http://example.com:8080",
            "ftp://graph.facebook.com", "https://user:secret@graph.facebook.com", "https://graph.facebook.com?x=1",
            "https://graph.facebook.com#frag", "https:///path", "http://localhost.evil.com"})
    void rejectsPlaintextRemoteHostsAndUnsafeUrls(String url) {
        assertThatThrownBy(() -> new GraphApiProperties(URI.create(url), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsANonPositiveTimeout() {
        assertThatThrownBy(() -> new GraphApiProperties(null, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
