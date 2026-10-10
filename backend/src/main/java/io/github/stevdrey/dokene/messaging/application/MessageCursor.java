// ABOUTME: Opaque exclusive cursor for the message list, ordered created_at DESC then id DESC (spec 0002 AC-8).
// ABOUTME: Base64url of "createdAt|messageId", like CustomerCursor; a value that does not decode is invalid input.
package io.github.stevdrey.dokene.messaging.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MessageCursor(Instant createdAt, UUID messageId) {
    private static final Instant MIN_INSTANT = Instant.EPOCH;
    private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999999Z");

    public MessageCursor {
        Objects.requireNonNull(createdAt, "Cursor instant is required");
        Objects.requireNonNull(messageId, "Cursor message id is required");
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((createdAt + "|" + messageId).getBytes(StandardCharsets.UTF_8));
    }

    /** Empty when the value is not a cursor this module produced. */
    public static Optional<MessageCursor> decode(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) {
                return Optional.empty();
            }
            MessageCursor cursor = new MessageCursor(Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
            // Only a value this module produced is a cursor: canonical form, and an instant the column can hold.
            if (!cursor.encode().equals(value) || cursor.createdAt.isBefore(MIN_INSTANT)
                    || cursor.createdAt.isAfter(MAX_INSTANT)) {
                return Optional.empty();
            }
            return Optional.of(cursor);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException malformed) {
            return Optional.empty();
        }
    }
}
