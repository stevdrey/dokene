package io.github.stevdrey.dokene.followup.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

public record FollowUpQueueCursor(LocalDate dueDate, UUID customerId) {
    public FollowUpQueueCursor {
        Objects.requireNonNull(dueDate, "Due date is required");
        Objects.requireNonNull(customerId, "Customer ID is required");
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((dueDate + "|" + customerId).getBytes(StandardCharsets.UTF_8));
    }

    public static FollowUpQueueCursor decode(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid queue cursor");
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid queue cursor");
            }
            return new FollowUpQueueCursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid queue cursor", exception);
        }
    }
}
