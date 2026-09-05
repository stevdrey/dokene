package io.github.stevdrey.dokene.audit.application;

/** Deliberately excludes database error text and payloads from the exception chain. */
public final class AuditPersistenceException extends RuntimeException {
    public AuditPersistenceException() {
        super("Audit persistence unavailable");
    }
}
