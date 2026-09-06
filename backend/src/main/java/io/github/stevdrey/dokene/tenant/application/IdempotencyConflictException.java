package io.github.stevdrey.dokene.tenant.application;

/**
 * Thrown when an idempotency key is reused with mismatched parameters.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
