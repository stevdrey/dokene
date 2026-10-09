// ABOUTME: Normalized outcome of one provider send call (ADR 0023 §1).
// ABOUTME: An unknown outcome may still carry the id the provider returned before the connection dropped.
package io.github.stevdrey.dokene.messaging.domain;

import java.util.Objects;
import java.util.Optional;

public sealed interface ProviderSendResult {

    record Accepted(String providerMessageId) implements ProviderSendResult {
        public Accepted {
            if (providerMessageId == null || providerMessageId.isBlank() || providerMessageId.length() > 128) {
                throw new IllegalArgumentException("Provider message id is required");
            }
        }
    }

    record RejectedPermanently(FailureCategory category) implements ProviderSendResult {
        public RejectedPermanently {
            Objects.requireNonNull(category, "Failure category is required");
        }
    }

    record FailedTransiently(FailureCategory category) implements ProviderSendResult {
        public FailedTransiently {
            Objects.requireNonNull(category, "Failure category is required");
        }
    }

    record OutcomeUnknown(Optional<String> providerMessageId) implements ProviderSendResult {
        public OutcomeUnknown {
            Objects.requireNonNull(providerMessageId, "Provider message id option is required");
            providerMessageId.ifPresent(id -> {
                if (id.isBlank() || id.length() > 128) {
                    throw new IllegalArgumentException("Provider message id is malformed");
                }
            });
        }
    }
}
