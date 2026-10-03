package io.github.stevdrey.dokene.ai.application;

/**
 * Caller-supplied check run immediately before every retry attempt (never before the first one). The retry loop
 * stays provider-only, so it cannot know about tenants or authorization; the caller passes this guard to
 * re-validate authorization before customer context is sent to the provider again. A guard that throws aborts the
 * retry and its exception propagates to the caller unchanged.
 *
 * <p>{@code previousAttempt} is the (validated, privacy-safe) metadata of the attempt that just failed, so a caller
 * that aborts the retry can still attribute the terminal outcome to the provider/model that was actually called.
 */
@FunctionalInterface
public interface RetryGuard {
    void beforeRetry(AiInvocationMetadata previousAttempt);

    static RetryGuard none() {
        return previousAttempt -> {
        };
    }
}
