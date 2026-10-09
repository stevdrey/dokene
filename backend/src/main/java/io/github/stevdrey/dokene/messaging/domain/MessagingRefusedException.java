// ABOUTME: The only refusal the messaging module throws; carries one closed error code.
// ABOUTME: The message is the code name only, so no body, phone or note can leak through it.
package io.github.stevdrey.dokene.messaging.domain;

import java.util.Objects;

public class MessagingRefusedException extends RuntimeException {
    private final MessagingErrorCode code;

    public MessagingRefusedException(MessagingErrorCode code) {
        super(Objects.requireNonNull(code, "Error code is required").name());
        this.code = code;
    }

    public MessagingErrorCode code() {
        return code;
    }
}
