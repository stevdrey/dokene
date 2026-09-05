package io.github.stevdrey.dokene.audit.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditCursor(Instant timestamp, UUID id) {
    public AuditCursor {
        Objects.requireNonNull(timestamp, "Cursor timestamp is required");
        Objects.requireNonNull(id, "Cursor ID is required");
    }
}
