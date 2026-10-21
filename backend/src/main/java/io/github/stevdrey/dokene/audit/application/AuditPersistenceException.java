package io.github.stevdrey.dokene.audit.application;

/**
 * Deliberately excludes database error text, payloads, and stack traces from a potentially frequent outage path.
 * Only whether the database itself was unreachable survives, so the HTTP boundary can add {@code Retry-After}
 * (ADR 0029).
 */
public final class AuditPersistenceException extends RuntimeException {
    private final boolean databaseUnavailable;

    public AuditPersistenceException() {
        this(false);
    }

    public AuditPersistenceException(boolean databaseUnavailable) {
        super("Audit persistence unavailable", null, false, false);
        this.databaseUnavailable = databaseUnavailable;
    }

    /** True when the write failed because no database connection was available, not because of the event. */
    public boolean databaseUnavailable() {
        return databaseUnavailable;
    }
}
