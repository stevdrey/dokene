package io.github.stevdrey.dokene.customer.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ContactPolicy(CustomerId customerId, long version, boolean doNotContact,
        ContactIntentSource doNotContactSource, Instant doNotContactChangedAt, List<ContactConsent> consents) {
    public ContactPolicy {
        Objects.requireNonNull(customerId, "Customer ID is required");
        if (version < 0) {
            throw new IllegalArgumentException("Contact policy version cannot be negative");
        }
        if ((doNotContactSource == null) != (doNotContactChangedAt == null)) {
            throw new IllegalArgumentException("Do-not-contact evidence is incomplete");
        }
        consents = List.copyOf(consents);
    }
}
