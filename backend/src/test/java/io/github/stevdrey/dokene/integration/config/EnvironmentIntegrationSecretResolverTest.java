// ABOUTME: Unit tests for the environment backed resolver: present, blank and missing names (spec 0005 AC-7).
// ABOUTME: The value is read at the moment of the call, so a change in the environment is seen by the next call.
package io.github.stevdrey.dokene.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class EnvironmentIntegrationSecretResolverTest {
    private final MockEnvironment environment = new MockEnvironment();
    private final EnvironmentIntegrationSecretResolver resolver = new EnvironmentIntegrationSecretResolver(environment);

    @Test
    void resolvesADefinedNameAndAnswersEmptyForBlankOrMissing() {
        environment.setProperty("DOKENE_WA_A", "token-a");
        environment.setProperty("DOKENE_WA_BLANK", "   ");

        assertThat(resolver.resolve(SecretRef.parse("env:DOKENE_WA_A"))).contains(new SecretValue("token-a"));
        assertThat(resolver.resolve(SecretRef.parse("env:DOKENE_WA_BLANK"))).isEmpty();
        assertThat(resolver.resolve(SecretRef.parse("env:DOKENE_WA_MISSING"))).isEmpty();
    }

    @Test
    void readsTheCurrentValueOnEveryCall() {
        SecretRef ref = SecretRef.parse("env:DOKENE_WA_ROTATED");
        environment.setProperty("DOKENE_WA_ROTATED", "first");
        assertThat(resolver.resolve(ref)).contains(new SecretValue("first"));

        environment.setProperty("DOKENE_WA_ROTATED", "second");
        assertThat(resolver.resolve(ref)).contains(new SecretValue("second"));
    }
}
