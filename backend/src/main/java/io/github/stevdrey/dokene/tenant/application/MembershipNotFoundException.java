package io.github.stevdrey.dokene.tenant.application;

public final class MembershipNotFoundException extends RuntimeException {
    public MembershipNotFoundException() {
        super("Membership is unavailable", null, false, false);
    }
}
