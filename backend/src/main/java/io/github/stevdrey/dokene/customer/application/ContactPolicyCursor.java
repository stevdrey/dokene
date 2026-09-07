package io.github.stevdrey.dokene.customer.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public record ContactPolicyCursor(Instant occurredAt, UUID id) {
    public ContactPolicyCursor {
        Objects.requireNonNull(occurredAt, "Cursor timestamp is required");
        Objects.requireNonNull(id, "Cursor ID is required");
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((occurredAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static ContactPolicyCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Invalid contact policy cursor");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid contact policy cursor");
            }
            return new ContactPolicyCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid contact policy cursor");
        }
    }
}
