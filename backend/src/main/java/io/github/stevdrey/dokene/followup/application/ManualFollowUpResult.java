package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.ManualFollowUpCompletion;

public record ManualFollowUpResult(ManualFollowUpCompletion completion, boolean created) { }
