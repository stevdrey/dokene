// ABOUTME: The only refusal the messaging module throws; carries one closed error code.
// ABOUTME: The message is the code name only, so no body, phone or note can leak through it.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public class MessagingRefusedException extends RuntimeException {
    private final MessagingErrorCode code;
    private final Duration retryAfter;

    public MessagingRefusedException(MessagingErrorCode code) {
        this(code, null);
    }

    /** A RATE_LIMITED refusal names how long the caller should wait; every other code carries none. */
    public MessagingRefusedException(MessagingErrorCode code, Duration retryAfter) {
        super(Objects.requireNonNull(code, "Error code is required").name());
        if (retryAfter != null && (code != MessagingErrorCode.RATE_LIMITED || retryAfter.isNegative())) {
            throw new IllegalArgumentException("Only a rate limit refusal carries a non negative retry delay");
        }
        this.code = code;
        this.retryAfter = retryAfter;
    }

    public MessagingErrorCode code() {
        return code;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
