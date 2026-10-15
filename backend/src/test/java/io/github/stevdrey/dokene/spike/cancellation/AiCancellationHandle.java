package io.github.stevdrey.dokene.spike.cancellation;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spike prototype (Issue #180), test sources only. A cancel request may arrive before, during or after the
 * provider HTTP call is started: a cancel that arrives first is remembered and applied when the call attaches.
 */
final class AiCancellationHandle {
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Future<?>> inFlight = new AtomicReference<>();

    /** Returns {@code true} only for the call that actually requested the cancellation. */
    boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return false;
        }
        Future<?> call = inFlight.get();
        if (call != null) {
            call.cancel(true);
        }
        return true;
    }

    void attach(Future<?> call) {
        inFlight.set(call);
        if (cancelled.get()) {
            call.cancel(true);
        }
    }

    boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Whether the provider call was really prevented or aborted: cancelled before it started, or its HTTP future was
     * cancelled before it completed. A cancel that loses the race against completion is not an abort, so the
     * invocation keeps its real outcome. The future's own atomic completion decides the race.
     */
    boolean aborted() {
        if (!cancelled.get()) {
            return false;
        }
        Future<?> call = inFlight.get();
        return call == null || call.isCancelled();
    }
}
