// ABOUTME: Unit tests for the resolved secret holder: redacted toString, equality by content (spec 0005 AC-7).
// ABOUTME: The value is readable only through the plain characters() method.
package io.github.stevdrey.dokene.integration.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Serializable;
import org.junit.jupiter.api.Test;

class SecretValueTest {

    @Test
    void printsRedactedAndComparesByContent() {
        SecretValue value = new SecretValue("token-123");

        assertThat(value.toString()).isEqualTo("SecretValue[redacted]");
        assertThat(String.valueOf(value)).doesNotContain("token-123");
        assertThat(value.characters()).isEqualTo("token-123");
        assertThat(value).isEqualTo(new SecretValue("token-123")).hasSameHashCodeAs(new SecretValue("token-123"))
                .isNotEqualTo(new SecretValue("other"));
        assertThat(value).isNotInstanceOf(Serializable.class);
        assertThat(SecretValue.class.isRecord()).isFalse();
    }
}
