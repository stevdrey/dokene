package io.github.stevdrey.dokene.customer.domain;

import java.util.Objects;
import java.util.UUID;

public record CustomerId(UUID value) {
    public CustomerId {
        Objects.requireNonNull(value, "Customer ID is required");
    }
}
