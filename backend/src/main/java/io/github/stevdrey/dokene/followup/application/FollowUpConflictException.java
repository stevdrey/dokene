package io.github.stevdrey.dokene.followup.application;

public class FollowUpConflictException extends RuntimeException {
    public FollowUpConflictException() {
        super("Follow-up state conflict");
    }
}
