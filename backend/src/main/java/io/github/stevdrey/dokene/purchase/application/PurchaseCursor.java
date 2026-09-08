package io.github.stevdrey.dokene.purchase.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record PurchaseCursor(Instant purchasedAt, UUID id) {
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((purchasedAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }
    public static PurchaseCursor decode(String encoded) {
        try {
            if (encoded == null || encoded.isBlank()) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            return new PurchaseCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid purchase cursor");
        }
    }
}
