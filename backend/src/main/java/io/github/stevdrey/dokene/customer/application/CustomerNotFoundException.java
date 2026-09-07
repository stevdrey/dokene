package io.github.stevdrey.dokene.customer.application;

public final class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException() {
        super("Customer is unavailable", null, false, false);
    }
}
