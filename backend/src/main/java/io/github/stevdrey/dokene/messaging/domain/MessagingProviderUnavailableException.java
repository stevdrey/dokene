// ABOUTME: The provider health refusal (PROVIDER_UNAVAILABLE, 503, ADR 0038): the send was not attempted, and the
// ABOUTME: delay says when to ask again, so the route can answer with Retry-After. Nothing about other sends leaks.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Duration;
import java.util.Objects;

public class MessagingProviderUnavailableException extends MessagingRefusedException {
    private final Duration retryAfter;

    public MessagingProviderUnavailableException(Duration retryAfter) {
        super(MessagingErrorCode.PROVIDER_UNAVAILABLE);
        this.retryAfter = Objects.requireNonNull(retryAfter, "Retry delay is required");
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
