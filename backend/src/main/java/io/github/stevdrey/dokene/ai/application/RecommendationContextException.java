package io.github.stevdrey.dokene.ai.application;

/** Safe failure without customer content in the message. */
public final class RecommendationContextException extends RuntimeException {
    public enum Reason { TOO_LARGE, UNSUPPORTED }

    private final Reason reason;

    public RecommendationContextException(Reason reason) {
        super("Recommendation context cannot be assembled: " + reason);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
