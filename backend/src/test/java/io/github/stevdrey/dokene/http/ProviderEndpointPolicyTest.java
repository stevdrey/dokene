// ABOUTME: Pins the shared provider endpoint policy's own construction (issue 440): a provider host that could never
// ABOUTME: match a lower-cased parsed host, or a missing property name, fails when the policy is built.
package io.github.stevdrey.dokene.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderEndpointPolicyTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"API.Example.com", "api.example.com.", "https://api.example.com", "10.0.0.5"})
    void refusesAProviderHostThatIsNotAnExactLowerCaseHostName(String providerHost) {
        assertThatThrownBy(() -> new ProviderEndpointPolicy(providerHost, "base-url", "trusted-hosts"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("providerHost must be an exact lower-case host name");
    }

    @Test
    void requiresBothPropertyNames() {
        assertThatThrownBy(() -> new ProviderEndpointPolicy("api.example.com", null, "trusted-hosts"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProviderEndpointPolicy("api.example.com", "base-url", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void acceptsTheProviderHostOverHttps() {
        var policy = new ProviderEndpointPolicy("api.example.com", "base-url", "trusted-hosts");
        assertThat(policy.canonicalBaseUrl("https://API.example.com/v1", List.of()))
                .isEqualTo("https://api.example.com/v1");
    }
}
