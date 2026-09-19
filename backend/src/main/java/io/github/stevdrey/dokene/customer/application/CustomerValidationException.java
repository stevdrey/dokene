package io.github.stevdrey.dokene.customer.application;

import java.util.Objects;

public class CustomerValidationException extends IllegalArgumentException {
    private final String field;

    public CustomerValidationException(String field, String message) {
        super(message);
        this.field = Objects.requireNonNull(field, "Field is required");
    }

    public String field() {
        return field;
    }
}
