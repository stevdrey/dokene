package io.github.stevdrey.dokene.audit.domain;

import java.util.Objects;
import java.util.UUID;

public record AuditTarget(Type type, UUID id) {
    public enum Type { MEMBERSHIP }

    public AuditTarget {
        Objects.requireNonNull(type, "Resource type is required");
        Objects.requireNonNull(id, "Resource ID is required");
    }
}
