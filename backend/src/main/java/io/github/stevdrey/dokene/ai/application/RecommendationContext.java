package io.github.stevdrey.dokene.ai.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/** Already assembled, provider-neutral facts; no tenant identity or authorization claims. */
public record RecommendationContext(LocalDate tenantDate, int effectiveCadenceDays, Instant lastPurchaseAt) {
    public RecommendationContext {
        Objects.requireNonNull(tenantDate, "Tenant date is required");
        if (effectiveCadenceDays < 1) {
            throw new IllegalArgumentException("Effective cadence must be positive");
        }
    }
}
