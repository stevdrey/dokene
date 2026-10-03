package io.github.stevdrey.dokene.ai.application;

/**
 * Caller-supplied check run immediately before every retry attempt (never before the first one). The retry loop
 * stays provider-only, so it cannot know about tenants or authorization; the caller passes this guard to
 * re-validate authorization before customer context is sent to the provider again. A guard that throws aborts the
 * retry and its exception propagates to the caller unchanged.
 */
@FunctionalInterface
public interface RetryGuard {
    void beforeRetry();

    static RetryGuard none() {
        return () -> {
        };
    }
}
