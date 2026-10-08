package io.github.stevdrey.dokene.customer.domain;

import java.util.Objects;

public class InvalidCustomerDisplayNameException extends IllegalArgumentException {
    public enum Reason { REQUIRED, TOO_LONG, INVALID_CHARACTERS }

    private final Reason reason;

    public InvalidCustomerDisplayNameException(Reason reason) {
        super("Invalid customer display name");
        this.reason = Objects.requireNonNull(reason, "Reason is required");
    }

    public Reason reason() {
        return reason;
    }
}
