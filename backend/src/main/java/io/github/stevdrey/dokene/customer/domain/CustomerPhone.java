package io.github.stevdrey.dokene.customer.domain;

import java.util.Objects;
import java.util.UUID;

public record CustomerPhone(UUID id, String e164, boolean primary) {
    public CustomerPhone {
        Objects.requireNonNull(id, "Phone ID is required");
        Objects.requireNonNull(e164, "Normalized phone is required");
        if (!e164.matches("^\\+[1-9][0-9]{1,14}$")) {
            throw new IllegalArgumentException("Phone must use E.164 format");
        }
    }

    public static CustomerPhone create(String e164, boolean primary) {
        return new CustomerPhone(UUID.randomUUID(), e164, primary);
    }
}
