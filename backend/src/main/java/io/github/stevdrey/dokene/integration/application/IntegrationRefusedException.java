package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import java.util.Objects;

/** A refusal carrying only a closed code; the message never names ids, tenants or secret references. */
public class IntegrationRefusedException extends RuntimeException {
    private final MessagingErrorCode code;

    public IntegrationRefusedException(MessagingErrorCode code) {
        super(Objects.requireNonNull(code, "Code is required").name());
        this.code = code;
    }

    public MessagingErrorCode code() {
        return code;
    }
}
