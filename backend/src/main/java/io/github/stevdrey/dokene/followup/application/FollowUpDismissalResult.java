package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpDismissal;
import java.util.Objects;

public record FollowUpDismissalResult(FollowUpDismissal dismissal, boolean created) {
    public FollowUpDismissalResult {
        Objects.requireNonNull(dismissal, "Dismissal is required");
    }
}
