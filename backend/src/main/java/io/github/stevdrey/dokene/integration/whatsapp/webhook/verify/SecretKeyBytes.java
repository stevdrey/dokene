// ABOUTME: Turns a resolved secret's characters into UTF-8 key bytes for exactly one crypto call, then wipes them.
// ABOUTME: Shared by the signature and challenge verifiers so neither ever builds a String from the secret.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

final class SecretKeyBytes {
    static final String UNAVAILABLE = "Webhook verification is unavailable";

    private SecretKeyBytes() {
    }

    /** Receives the key bytes, which are valid only until it returns. */
    @FunctionalInterface
    interface KeyUse<T> {
        T apply(byte[] key) throws GeneralSecurityException;
    }

    /**
     * Encodes the secret through a {@link CharsetEncoder} into a buffer sized by {@code maxBytesPerChar}, copies
     * exactly the encoded bytes into the key array, runs {@code use}, then zero fills both arrays. The residual the
     * spec states: a {@code SecretKeySpec} or {@code Mac} built inside {@code use} keeps its own copy for its life.
     */
    static <T> T withUtf8Key(ResolvedSecret secret, KeyUse<T> use) {
        return secret.withValue(chars -> {
            CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
            byte[] backing = new byte[(int) Math.ceil(chars.length * encoder.maxBytesPerChar())];
            ByteBuffer buffer = ByteBuffer.wrap(backing);
            try {
                CoderResult encoded = encoder.encode(CharBuffer.wrap(chars), buffer, true);
                CoderResult flushed = encoder.flush(buffer);
                if (encoded.isError() || flushed.isError()) {
                    throw new IllegalStateException(UNAVAILABLE);
                }
                buffer.flip();
                byte[] key = new byte[buffer.remaining()];
                buffer.get(key);
                try {
                    return use.apply(key);
                } catch (GeneralSecurityException impossibleOnAStandardJdk) {
                    throw new IllegalStateException(UNAVAILABLE);
                } finally {
                    Arrays.fill(key, (byte) 0);
                }
            } finally {
                Arrays.fill(backing, (byte) 0);
            }
        });
    }
}
