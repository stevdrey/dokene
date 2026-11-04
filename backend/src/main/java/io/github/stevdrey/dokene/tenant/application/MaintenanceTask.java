package io.github.stevdrey.dokene.tenant.application;

import java.util.UUID;

/**
 * The cross-tenant maintenance task a maintenance capability authorizes. The subject ids are fixed by migration V35;
 * each function accepts only its own, so a capability for a read-only finder cannot drive the purge.
 */
public enum MaintenanceTask {
    INBOX_DUE(new UUID(0L, 1L)),
    INBOX_PURGE(new UUID(0L, 2L)),
    DISPATCH_RECOVERY(new UUID(0L, 3L));

    private final UUID subjectId;

    MaintenanceTask(UUID subjectId) {
        this.subjectId = subjectId;
    }

    public UUID subjectId() {
        return subjectId;
    }
}
