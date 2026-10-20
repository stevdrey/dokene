// ABOUTME: Proves spec 0007 AC-5 for the shared key path: UTF-8 encoding, wiping after use, and the fixed crypto failure.
// ABOUTME: Also pins the unencodable secret case the spec leaves implicit, so a 500 there is a decision, not an accident.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import static io.github.stevdrey.dokene.integration.testsupport.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SecretKeyBytesTest {
    private static final String UNAVAILABLE = "Webhook verification is unavailable";

    /** covers: AC-5, the key handed to the crypto call is exactly the UTF-8 encoding, multibyte characters included. */
    @Test
    void handsTheExactUtf8BytesOfTheSecretToTheUse() {
        String secret = "pässwörd€🙂-" + SecretCanaries.value();
        try (ResolvedSecret resolved = new ResolvedSecret(secret.toCharArray())) {
            byte[] seen = SecretKeyBytes.withUtf8Key(resolved, byte[]::clone);

            assertThat(seen).isEqualTo(secret.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** covers: AC-5, both the encoder buffer and the key array are zero filled once the use returns. */
    @Test
    void wipesTheKeyArrayAfterTheUseReturns() {
        AtomicReference<byte[]> handed = new AtomicReference<>();
        try (ResolvedSecret resolved = new ResolvedSecret(SecretCanaries.value().toCharArray())) {
            int length = SecretKeyBytes.withUtf8Key(resolved, key -> {
                handed.set(key);
                return key.length;
            });

            assertThat(length).isPositive();
            assertThat(handed.get()).containsOnly((byte) 0);
        }
    }

    /** covers: AC-5, the key is wiped even when the crypto call throws. */
    @Test
    void wipesTheKeyArrayWhenTheUseThrows() {
        AtomicReference<byte[]> handed = new AtomicReference<>();
        try (ResolvedSecret resolved = new ResolvedSecret(SecretCanaries.value().toCharArray())) {
            catchThrowableOfType(IllegalStateException.class, () -> SecretKeyBytes.withUtf8Key(resolved, key -> {
                handed.set(key);
                throw new NoSuchAlgorithmException("boom");
            }));

            assertThat(handed.get()).containsOnly((byte) 0);
        }
    }

    /** covers: AC-5, a GeneralSecurityException becomes the fixed IllegalStateException with no cause and no secret. */
    @Test
    void aCryptoFailureBecomesTheFixedIllegalStateWithoutACause() {
        String secret = SecretCanaries.value();
        try (ResolvedSecret resolved = new ResolvedSecret(secret.toCharArray())) {
            IllegalStateException failure = catchThrowableOfType(IllegalStateException.class,
                    () -> SecretKeyBytes.withUtf8Key(resolved, key -> {
                        throw new NoSuchAlgorithmException("engine said: " + secret);
                    }));

            assertThat(failure.getMessage()).isEqualTo(UNAVAILABLE);
            assertThat(failure.getCause()).isNull();
            assertThat(containsAny(failure.toString(), List.of(secret))).isFalse();
        }
    }

    /** covers: AC-5 residual, a secret that UTF-8 cannot encode is refused as unavailable before any crypto call. */
    @Test
    void anUnencodableSecretIsUnavailableAndNeverReachesTheUse() {
        String unpairedSurrogate = "ab\uD800cd-" + SecretCanaries.value();
        AtomicReference<Boolean> used = new AtomicReference<>(false);
        try (ResolvedSecret resolved = new ResolvedSecret(unpairedSurrogate.toCharArray())) {
            IllegalStateException failure = catchThrowableOfType(IllegalStateException.class,
                    () -> SecretKeyBytes.withUtf8Key(resolved, key -> {
                        used.set(true);
                        return null;
                    }));

            assertThat(failure.getMessage()).isEqualTo(UNAVAILABLE);
            assertThat(failure.getCause()).isNull();
            assertThat(used.get()).isFalse();
        }
    }

    /** covers: AC-5, the caller owns the ResolvedSecret; the key path neither closes it nor consumes it. */
    @Test
    void leavesTheResolvedSecretOpenForTheCallerToClose() {
        try (ResolvedSecret resolved = new ResolvedSecret(SecretCanaries.value().toCharArray())) {
            SecretKeyBytes.withUtf8Key(resolved, key -> null);

            int length = resolved.withValue(chars -> chars.length);
            assertThat(length).isPositive();
        }
    }
}
