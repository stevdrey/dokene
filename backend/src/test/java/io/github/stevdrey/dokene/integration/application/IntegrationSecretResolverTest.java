// ABOUTME: Proves secret material is wiped after scoped use and after close, and never printed (ADR 0024 §3).
// ABOUTME: Also pins the opaque secret reference grammar.
package io.github.stevdrey.dokene.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntegrationSecretResolverTest {
    @Test
    void theBufferHandedToTheCallbackIsWipedOnReturn() {
        var secret = new ResolvedSecret("s3cr3t".toCharArray());
        var seen = new AtomicReference<char[]>();

        String result = secret.withValue(buffer -> {
            seen.set(buffer);
            return new String(buffer);
        });

        assertThat(result).isEqualTo("s3cr3t");
        assertThat(seen.get()).containsOnly('\0');
    }

    @Test
    void theBufferIsWipedWhenTheCallbackThrows() {
        var secret = new ResolvedSecret("s3cr3t".toCharArray());
        var seen = new AtomicReference<char[]>();

        assertThatThrownBy(() -> secret.withValue(buffer -> {
            seen.set(buffer);
            throw new IllegalStateException("provider call failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(seen.get()).containsOnly('\0');
    }

    @Test
    void closeWipesTheSecretAndRefusesFurtherUse() {
        var secret = new ResolvedSecret("s3cr3t".toCharArray());
        String value = secret.withValue(String::new);
        assertThat(value).isEqualTo("s3cr3t");

        secret.close();

        assertThatThrownBy(() -> secret.withValue(String::new)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void neverPrintsTheValueAndRejectsEmptySecrets() {
        assertThat(new ResolvedSecret("s3cr3t".toCharArray())).hasToString("ResolvedSecret[REDACTED]");
        assertThatThrownBy(() -> new ResolvedSecret(new char[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secretReferencesAreOpaqueKeysOnly() {
        assertThat(new SecretReference("env:DOKENE_WHATSAPP_APP_SECRET").value())
                .isEqualTo("env:DOKENE_WHATSAPP_APP_SECRET");
        assertThatThrownBy(() -> new SecretReference("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretReference("has spaces")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecretReference("x".repeat(257))).isInstanceOf(IllegalArgumentException.class);
    }
}
