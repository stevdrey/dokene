package io.github.stevdrey.dokene.customer.application;

public final class CustomerConflictException extends RuntimeException {
    public CustomerConflictException() {
        super("Customer operation conflicts with current state", null, false, false);
    }
}
