// ABOUTME: Unit tests for the dokene.messaging.provider selection: blank is none, local is the adapter, else fail.
// ABOUTME: Covers spec 0003 AC-7 at the unit level.
package io.github.stevdrey.dokene.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessagingProviderTypeTest {

    @Test
    void blankSelectsNoProviderAndLocalSelectsTheAdapterCaseInsensitively() {
        assertThat(MessagingProviderType.fromProperty(null)).isEqualTo(MessagingProviderType.DISABLED);
        assertThat(MessagingProviderType.fromProperty("  ")).isEqualTo(MessagingProviderType.DISABLED);
        assertThat(MessagingProviderType.fromProperty("local")).isEqualTo(MessagingProviderType.LOCAL);
        assertThat(MessagingProviderType.fromProperty("LOCAL")).isEqualTo(MessagingProviderType.LOCAL);
    }

    @Test
    void anUnknownValueFailsNamingTheSupportedValues() {
        assertThatThrownBy(() -> MessagingProviderType.fromProperty("whatsap"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whatsap").hasMessageContaining("local");
    }
}
