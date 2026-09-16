package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;

public record FollowUpQueueQuery(FollowUpStatus statusFilter, FollowUpQueueCursor cursor, int limit) {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    public FollowUpQueueQuery {
        if (statusFilter != null && statusFilter != FollowUpStatus.DUE && statusFilter != FollowUpStatus.OVERDUE) {
            throw new IllegalArgumentException("Status filter must be DUE or OVERDUE");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public FollowUpQueueQuery(FollowUpStatus statusFilter, FollowUpQueueCursor cursor) {
        this(statusFilter, cursor, DEFAULT_LIMIT);
    }
}
