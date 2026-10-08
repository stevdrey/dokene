package io.github.stevdrey.dokene.customer.domain;

public class InvalidCustomerDisplayNameException extends IllegalArgumentException {
    public InvalidCustomerDisplayNameException() {
        super("Invalid customer display name");
    }
}
