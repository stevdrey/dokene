// ABOUTME: SHA-256 fingerprint of an idempotent request so a replay with different content is refused.
// ABOUTME: Canonical form: operation, then each field as 4 byte big endian length plus UTF-8 bytes; null is 0xFFFFFFFF.
package io.github.stevdrey.dokene.messaging.domain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public final class RequestFingerprint {
    private static final byte[] NULL_FIELD = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};

    private RequestFingerprint() {
    }

    /** Fields are encoded in the order given; a null entry differs from an empty string. */
    public static String of(MessageOperation operation, List<String> fields) {
        Objects.requireNonNull(operation, "Operation is required");
        Objects.requireNonNull(fields, "Fields are required");
        var canonical = new ByteArrayOutputStream();
        write(canonical, operation.name());
        for (String field : fields) {
            write(canonical, field);
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void write(ByteArrayOutputStream out, String field) {
        if (field == null) {
            out.writeBytes(NULL_FIELD);
            return;
        }
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }
}
