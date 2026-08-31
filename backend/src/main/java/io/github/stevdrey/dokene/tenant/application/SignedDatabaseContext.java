package io.github.stevdrey.dokene.tenant.application;

import java.time.Instant;
import java.util.Objects;

/**
 * Canonical, signed capability transported to PostgreSQL for one bounded operation scope.
 */
public record SignedDatabaseContext(String payload, String signature, Instant expiresAt) {

    public SignedDatabaseContext {
        Objects.requireNonNull(payload, "Database context payload is required");
        Objects.requireNonNull(signature, "Database context signature is required");
        Objects.requireNonNull(expiresAt, "Database context expiry is required");
    }
}
