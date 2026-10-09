// ABOUTME: A delivery fact reported by the provider for a stored provider message id.
// ABOUTME: A FAILED report without a category is stored as UNKNOWN.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record DeliveryStatusReport(String providerMessageId, DeliveryStatus status, Instant occurredAt,
        Optional<FailureCategory> failure) {
    public DeliveryStatusReport {
        if (providerMessageId == null || providerMessageId.isBlank() || providerMessageId.length() > 128) {
            throw new IllegalArgumentException("Provider message id is required");
        }
        Objects.requireNonNull(status, "Delivery status is required");
        Objects.requireNonNull(occurredAt, "Report time is required");
        Objects.requireNonNull(failure, "Failure option is required");
    }

    public FailureCategory failureOrUnknown() {
        return failure.orElse(FailureCategory.UNKNOWN);
    }
}
