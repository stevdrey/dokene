package io.github.stevdrey.dokene.purchase.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record PurchaseEventCursor(Instant occurredAt, UUID id) {
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((occurredAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
    public static PurchaseEventCursor decode(String encoded) {
        try {
            if (encoded == null || encoded.isBlank()) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new PurchaseEventCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid purchase history cursor");
        }
    }
}
