package io.github.stevdrey.dokene.spike.cancellation;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Spike prototype (Issue #180), test sources only. A cancel request may arrive before, during or after a provider
 * attempt, or between attempts (retry backoff). Starting an attempt and cancelling are mutually exclusive under one
 * lock: either the start sees the cancel and sends nothing, or the cancel sees the started call and aborts it.
 */
final class AiCancellationHandle {
    /** The current invocation's handle, bound per request with {@link ScopedValue#where}. */
    static final ScopedValue<AiCancellationHandle> CURRENT = ScopedValue.newInstance();

    private final Object lock = new Object();
    private boolean cancelled;
    private boolean prevented;
    private Future<?> inFlight;

    /** Returns {@code true} only for the call that actually requested the cancellation. */
    boolean cancel() {
        Future<?> call;
        synchronized (lock) {
            if (cancelled) {
                return false;
            }
            cancelled = true;
            call = inFlight;
        }
        if (call != null) {
            call.cancel(true);
        }
        return true;
    }

    /**
     * Starts one provider attempt unless the invocation is already cancelled. {@code start} must only enqueue the
     * call (non-blocking), because it runs under the lock. Each attempt replaces the previous attempt's future.
     *
     * @throws CancellationException if cancelled before this attempt started; nothing is sent
     */
    <F extends Future<?>> F start(Supplier<F> start) {
        synchronized (lock) {
            if (cancelled) {
                prevented = true;
                throw new CancellationException("AI invocation cancelled before the provider call started");
            }
            F call = start.get();
            inFlight = call;
            return call;
        }
    }

    boolean isCancelled() {
        synchronized (lock) {
            return cancelled;
        }
    }

    /**
     * Whether the cancel really prevented or aborted provider work: an attempt (first or retry) was not started, or
     * the current attempt's HTTP future was cancelled before it completed. A cancel that loses the race against
     * completion is not an abort, so the invocation keeps its real outcome. The future's own atomic completion
     * decides that race.
     */
    boolean aborted() {
        synchronized (lock) {
            return cancelled && (prevented || (inFlight != null && inFlight.isCancelled()));
        }
    }
}
