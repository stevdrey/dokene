package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpQueueItem;
import java.util.List;
import java.util.Objects;

public record FollowUpQueuePage(List<FollowUpQueueItem> items, String nextCursor) {
    public FollowUpQueuePage {
        items = List.copyOf(Objects.requireNonNull(items, "Items are required"));
    }
}
