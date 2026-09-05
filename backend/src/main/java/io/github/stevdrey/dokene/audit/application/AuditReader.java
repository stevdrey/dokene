package io.github.stevdrey.dokene.audit.application;

public interface AuditReader {
    default AuditPage read() {
        return read(null, 50);
    }

    /** Reads only the active tenant; limit must be between 1 and 100. */
    AuditPage read(AuditCursor before, int limit);
}
