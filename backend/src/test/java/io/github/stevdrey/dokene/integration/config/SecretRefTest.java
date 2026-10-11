// ABOUTME: Unit tests for the secret reference grammar (spec 0005 AC-7): prefix, scheme and case are all enforced.
// ABOUTME: Anything that could name a variable outside DOKENE_WA_ is refused before it reaches a resolver.
package io.github.stevdrey.dokene.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class SecretRefTest {

    @Test
    void parsesAReferenceUnderThePrefix() {
        SecretRef ref = SecretRef.parse("env:DOKENE_WA_TENANT_A");

        assertThat(ref.scheme()).isEqualTo("env");
        assertThat(ref.name()).isEqualTo("DOKENE_WA_TENANT_A");
        assertThat(ref.value()).isEqualTo("env:DOKENE_WA_TENANT_A");
        assertThat(SecretRef.parse("env:DOKENE_WA_1")).isEqualTo(new SecretRef("env", "DOKENE_WA_1"));
        assertThat(SecretRef.parse("env:DOKENE_WA_" + "A".repeat(100)).name()).hasSize(110);
    }

    @ParameterizedTest
    @ValueSource(strings = {"vault:DOKENE_WA_A", "env:SPRING_DATASOURCE_PASSWORD", "env:DOKENE_WA_lower",
            "env:DOKENE_WA_", "ENV:DOKENE_WA_A", "env:DOKENE_WA_A B", "env:DOKENE_WA_A-B", "DOKENE_WA_A",
            "env:DOKENE_WA_A\n", "env:DOKENE_TENANT_CONTEXT_SIGNING_KEY", "env:dokene_wa_a", ""})
    void refusesEveryOtherScheme_shapeOrCase(String value) {
        assertThatThrownBy(() -> SecretRef.parse(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesANameLongerThanTheGrammarAllows() {
        assertThatThrownBy(() -> SecretRef.parse("env:DOKENE_WA_" + "A".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretRef.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretRef("env", "OTHER")).isInstanceOf(IllegalArgumentException.class);
    }
}
