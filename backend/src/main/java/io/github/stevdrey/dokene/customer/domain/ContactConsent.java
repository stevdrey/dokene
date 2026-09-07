package io.github.stevdrey.dokene.customer.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ContactConsent(UUID contactId, ContactChannel channel, ConsentStatus status,
        ContactIntentSource source, Instant changedAt) {
    public ContactConsent {
        Objects.requireNonNull(contactId, "Contact ID is required");
        Objects.requireNonNull(channel, "Contact channel is required");
        Objects.requireNonNull(status, "Consent status is required");
        if (status == ConsentStatus.UNKNOWN && (source != null || changedAt != null)) {
            throw new IllegalArgumentException("Unknown consent cannot have evidence");
        }
        if (status != ConsentStatus.UNKNOWN) {
            Objects.requireNonNull(source, "Consent source is required");
            Objects.requireNonNull(changedAt, "Consent timestamp is required");
        }
    }

    public static ContactConsent unknown(UUID contactId, ContactChannel channel) {
        return new ContactConsent(contactId, channel, ConsentStatus.UNKNOWN, null, null);
    }
}
