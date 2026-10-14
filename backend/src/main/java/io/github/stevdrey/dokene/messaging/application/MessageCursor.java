// ABOUTME: Opaque keyset cursor for GET /api/messages: the createdAt and id of the last item already returned.
// ABOUTME: Encoded like the other list cursors (ADR 0009); a malformed cursor is invalid input, never a fallback.
package io.github.stevdrey.dokene.messaging.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public record MessageCursor(Instant createdAt, UUID id) {
    public MessageCursor {
        Objects.requireNonNull(createdAt, "Cursor timestamp is required");
        Objects.requireNonNull(id, "Cursor id is required");
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static MessageCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Invalid message cursor");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid message cursor");
            }
            return new MessageCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid message cursor");
        }
    }
}
