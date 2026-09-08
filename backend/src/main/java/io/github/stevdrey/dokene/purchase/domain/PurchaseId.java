package io.github.stevdrey.dokene.purchase.domain;

import java.util.Objects;
import java.util.UUID;

public record PurchaseId(UUID value) {
    public PurchaseId {
        Objects.requireNonNull(value, "Purchase ID is required");
    }
}
