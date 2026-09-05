package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

public record AuditPage(List<AuditEvent> events, Optional<AuditCursor> nextCursor) {
    public AuditPage {
        events = List.copyOf(events);
        nextCursor = Objects.requireNonNull(nextCursor, "Next cursor is required");
    }
}
