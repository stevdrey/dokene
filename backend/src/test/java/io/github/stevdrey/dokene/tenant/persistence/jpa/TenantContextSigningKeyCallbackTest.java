// ABOUTME: Unit checks of the signing-key callback configuration (issue 324): the retirement grace is bounded and
// ABOUTME: configuration errors never echo key material.
package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TenantContextSigningKeyCallbackTest {
    private static final String KEY = "a".repeat(64);

    @Test
    void acceptsAGraceFromZeroToSevenDays() {
        assertThatCode(() -> new TenantContextSigningKeyCallback(KEY, "k1", Duration.ZERO)).doesNotThrowAnyException();
        assertThatCode(() -> new TenantContextSigningKeyCallback(KEY, "k1", Duration.ofDays(7)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsANegativeMissingOrUnboundedGrace() {
        for (Duration grace : new Duration[] {Duration.ofSeconds(-1), Duration.ofDays(7).plusSeconds(1), null}) {
            assertThatThrownBy(() -> new TenantContextSigningKeyCallback(KEY, "k1", grace))
                    .as(String.valueOf(grace))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("DOKENE_TENANT_CONTEXT_KEY_RETIREMENT_GRACE");
        }
    }

    @Test
    void rejectsAMalformedKeyWithoutEchoingIt() {
        String malformed = "b".repeat(63);
        assertThatThrownBy(() -> new TenantContextSigningKeyCallback(malformed, "k1", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(malformed);
    }
}
