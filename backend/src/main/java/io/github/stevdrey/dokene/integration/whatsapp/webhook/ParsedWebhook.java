// ABOUTME: Typed result of parsing one verified webhook body: the delivery status updates and a count of the rest.
// ABOUTME: Holds the phone number id only to resolve the tenant; no text, phone or provider JSON survives parsing.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ParsedWebhook(List<StatusUpdate> updates, int unsupportedCount) {
    public ParsedWebhook {
        updates = List.copyOf(Objects.requireNonNull(updates, "Updates are required"));
        if (unsupportedCount < 0) {
            throw new IllegalArgumentException("Unsupported count cannot be negative");
        }
    }

    /** One status of one provider message, as Meta reported it for the business phone number it names. */
    public record StatusUpdate(String phoneNumberId, String providerMessageId, DeliveryStatus status,
            Instant occurredAt, Optional<FailureCategory> failure) {
        static final int MAX_PROVIDER_MESSAGE_ID_LENGTH = 128;

        public StatusUpdate {
            Objects.requireNonNull(phoneNumberId, "Phone number id is required");
            Objects.requireNonNull(providerMessageId, "Provider message id is required");
            Objects.requireNonNull(status, "Status is required");
            Objects.requireNonNull(occurredAt, "Report time is required");
            Objects.requireNonNull(failure, "Failure option is required");
        }

        @Override
        public String toString() {
            // Ids are opaque provider data; keep them out of any accidental log line.
            return "StatusUpdate[status=" + status + ", failure=" + failure.map(Enum::name).orElse("none") + "]";
        }
    }
}
