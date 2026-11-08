package io.github.stevdrey.dokene.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OpenAiEndpointPolicyTest {

    private static OpenAiProviderProperties of(String baseUrl, String... trusted) {
        return new OpenAiProviderProperties("key", null, baseUrl, null, null, null, List.of(trusted));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void anUnsetBaseUrlIsTheOpenAiEndpoint(String baseUrl) {
        assertThat(of(baseUrl).baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(new OpenAiProviderProperties("key", null, null, null, null, null).baseUrl())
                .isEqualTo("https://api.openai.com/v1");
    }

    @ParameterizedTest
    @CsvSource({
            "https://api.openai.com/v1, https://api.openai.com/v1",
            "HTTPS://API.OPENAI.COM/v1, https://api.openai.com/v1",
            "https://api.openai.com, https://api.openai.com",
            "http://127.0.0.1:18080/v1, http://127.0.0.1:18080/v1",
            "http://localhost:9000/v1, http://localhost:9000/v1",
            "https://localhost:9443/v1, https://localhost:9443/v1",
            "http://[::1]:9000/v1, http://[::1]:9000/v1",
            "https://api.openai.com:443/v1, https://api.openai.com/v1",
            "https://api.openai.com:443/v1/./x/../, https://api.openai.com/v1/",
            "http://localhost:80/v1, http://localhost/v1",
            "https://localhost:80/v1, https://localhost:80/v1"})
    void acceptsOpenAiOverHttpsAndExactLoopbackTestServers(String configured, String canonical) {
        assertThat(of(configured).baseUrl()).isEqualTo(canonical);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // cleartext or another scheme towards OpenAI
            "http://api.openai.com/v1", "ftp://api.openai.com/v1", "ws://api.openai.com/v1",
            // look-alike, parent, sibling and sub-domain hosts
            "https://api.openai.com.evil.example/v1", "https://evilapi.openai.com/v1", "https://openai.com/v1",
            "https://chat.api.openai.com/v1", "https://api.openai.com./v1", "https://example.com/v1",
            // user-info tricks and credentials in the URL
            "https://api.openai.com@evil.example/v1", "https://api.openai.com:443@evil.example/v1",
            "https://user:secret@api.openai.com/v1", "https://user@api.openai.com/v1",
            // query and fragment, empty ones included
            "https://api.openai.com/v1?x=1", "https://api.openai.com/v1?", "https://api.openai.com/v1#frag",
            "https://api.openai.com/v1#", "https://evil.example#@api.openai.com/v1",
            // parser-differential and encoding tricks
            "https://api.openai.com\\@evil.example/v1", "https://api%2eopenai%2ecom/v1",
            "https://api.openai.com%2f@evil.example/v1", "https://api.open_ai.com/v1", "https://ápi.openai.com/v1",
            "https://api.openai.com /v1", "https://api.openai.com\t/v1", "https://api.openai.com\n/v1",
            // loopback look-alikes, private and non-canonical addresses, other loopback names
            "http://localhost.evil.example/v1", "http://stub.localhost/v1", "http://localhost./v1",
            "http://127.1/v1", "http://0x7f000001/v1", "http://2130706433/v1", "http://127.0.0.2/v1",
            "http://0.0.0.0/v1", "http://[::ffff:127.0.0.1]/v1", "http://10.0.0.5/v1", "https://10.0.0.5/v1",
            "http://169.254.169.254/latest", "ftp://localhost/v1",
            // malformed, relative, opaque or host-less values and bad ports
            "api.openai.com/v1", "//api.openai.com/v1", "/v1", "https:///v1", "https://", "mailto:a@api.openai.com",
            "https://api.openai.com:0/v1", "https://api.openai.com:70000/v1", "https://api.openai.com:x/v1",
            "https://[::1/v1", "not a url"})
    void rejectsEveryOtherEndpointBeforeAnyClientIsBuilt(String baseUrl) {
        assertThatThrownBy(() -> of(baseUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("dokene.ai.openai.base-url must be https on api.openai.com");
    }

    @Test
    void theRejectionNeverEchoesTheConfiguredValue() {
        assertThatThrownBy(() -> of("https://user:s3cr3t-token@evil.example/v1?key=s3cr3t-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("s3cr3t-token").doesNotContain("evil.example");
    }

    @Test
    void aTrustedGatewayIsAcceptedOnlyWhenExplicitlyListedAndOverHttps() {
        assertThat(of("https://ai-gateway.internal.example/openai/v1", "ai-gateway.internal.example").baseUrl())
                .isEqualTo("https://ai-gateway.internal.example/openai/v1");
        assertThat(of("https://AI-Gateway.internal.example:8443/v1", "ai-gateway.internal.example").baseUrl())
                .isEqualTo("https://ai-gateway.internal.example:8443/v1");
        assertThat(of("https://ai-gateway.internal.example/v1", "ai-gateway.internal.example")
                .additionalTrustedHosts()).containsExactly("ai-gateway.internal.example");

        assertThatThrownBy(() -> of("https://ai-gateway.internal.example/v1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("https://ai-gateway.internal.example/v1", "other.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("http://ai-gateway.internal.example/v1", "ai-gateway.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
        // A listed host widens the match to neither its sub-domains nor its parents.
        assertThatThrownBy(() -> of("https://a.ai-gateway.internal.example/v1", "ai-gateway.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("https://internal.example/v1", "ai-gateway.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
        // Credentials, query and fragment stay forbidden for a listed host.
        assertThatThrownBy(() -> of("https://u:p@ai-gateway.internal.example/v1", "ai-gateway.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> of("https://ai-gateway.internal.example/v1?k=v", "ai-gateway.internal.example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://gateway.example", "gateway.example:8443", "*.example.com", "Gateway.Example",
            "gateway.example/path", "", " ", "gateway..example", "-gateway.example", "gateway.example.",
            "user@gateway.example", "10.0.0.5", "169.254.169.254", "127.1", "2130706433"})
    void rejectsMalformedTrustedHostEntries(String entry) {
        assertThatThrownBy(() -> of("https://api.openai.com/v1", entry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("dokene.ai.openai.additional-trusted-hosts entries must be exact lower-case host names");
    }

    @Test
    void toStringNeverExposesTheApiKeyOrTrustedHosts() {
        String text = new OpenAiProviderProperties("sk-test-s3cr3t", null, "https://gw.internal.example/v1", null,
                null, null, List.of("gw.internal.example")).toString();
        assertThat(text).isEqualTo("OpenAiProviderProperties[REDACTED]")
                .doesNotContain("sk-test-s3cr3t").doesNotContain("gw.internal.example");
    }

    @Test
    void rejectsANullTrustedHostEntry() {
        java.util.ArrayList<String> entries = new java.util.ArrayList<>();
        entries.add(null);
        assertThatThrownBy(() -> new OpenAiProviderProperties("key", null, null, null, null, null, entries))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
