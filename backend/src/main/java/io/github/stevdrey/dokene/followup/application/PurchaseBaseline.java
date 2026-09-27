package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import java.time.Instant;
import java.util.Objects;

/**
 * Deterministic baseline metadata for a purchase included in an advisory AI model context.
 * Used by the AI Action Gate to verify that no purchase in the model context was mutated,
 * corrected, or voided while the provider request was in flight.
 */
public record PurchaseBaseline(
        PurchaseId id,
        long version,
        Instant purchasedAt
) {
    public PurchaseBaseline {
        Objects.requireNonNull(id, "Purchase ID is required");
        Objects.requireNonNull(purchasedAt, "Purchased-at timestamp is required");
        if (version < 0) {
            throw new IllegalArgumentException("Purchase version cannot be negative");
        }
    }

    public static PurchaseBaseline from(Purchase purchase) {
        Objects.requireNonNull(purchase, "Purchase is required");
        return new PurchaseBaseline(purchase.id(), purchase.version(), purchase.purchasedAt());
    }
}
