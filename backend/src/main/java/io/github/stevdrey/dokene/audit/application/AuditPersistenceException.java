package io.github.stevdrey.dokene.audit.application;

/**
 * Deliberately excludes database error text, payloads, and stack traces from a potentially frequent outage path.
 */
public final class AuditPersistenceException extends RuntimeException {
    public AuditPersistenceException() {
        super("Audit persistence unavailable", null, false, false);
    }
}
