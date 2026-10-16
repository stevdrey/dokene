package io.github.stevdrey.dokene.spike.cancellation;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spike prototype (Issue #180), test sources only. Runs each provider attempt on its own virtual thread, started
 * through the invocation's {@link AiCancellationHandle}; a cancel interrupts that worker. On a virtual thread an
 * interrupt during blocking {@code java.net.Socket} I/O closes the socket, so this covers every phase of the HTTP
 * exchange (connect, waiting for headers, reading the body) without depending on SDK internals. The request thread
 * only waits and is never interrupted. Sits under the retry decorator, so every attempt goes through the handle.
 * <p>
 * The worker does not inherit {@link ScopedValue} bindings; anything the delegate reads from them (the correlation
 * id) must be captured on the request thread and rebound in the worker. The spike's delegate needs none.
 */
final class VirtualThreadCancellableProvider implements AiProvider {
    private final AiProvider delegate;

    VirtualThreadCancellableProvider(AiProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate provider is required");
    }

    @Override
    public AiRecommendationResponse recommend(AiRecommendationRequest request) {
        return run(() -> delegate.recommend(request));
    }

    @Override
    public AiDraftResponse draft(AiDraftRequest request) {
        return run(() -> delegate.draft(request));
    }

    @Override
    public Duration defaultTimeout() {
        return delegate.defaultTimeout();
    }

    @Override
    public Duration maxTimeout() {
        return delegate.maxTimeout();
    }

    private <T> T run(Callable<T> attempt) {
        // ScopedValue.orElse rejects a null default, so check the binding explicitly.
        if (!AiCancellationHandle.CURRENT.isBound()) {
            return call(attempt);
        }
        AiCancellationHandle handle = AiCancellationHandle.CURRENT.get();
        WorkerAttempt<T> worker;
        try {
            worker = handle.start(() -> WorkerAttempt.start(attempt));
        } catch (CancellationException e) {
            throw notStarted();
        }
        try {
            // The attempt's own terminal result: on a cancel, the delegate's CANCELLED exception with its
            // provider/model metadata, not a bare CancellationException.
            return worker.result.get();
        } catch (InterruptedException e) {
            worker.cancel(true);
            Thread.currentThread().interrupt();
            throw notStarted();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            if (worker.cancelWon()) {
                throw asCancelled(e.getCause());
            }
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Provider attempt failed", e.getCause());
        }
    }

    /**
     * A cancel was requested before the attempt finished and it did not complete normally, so its outcome is
     * CANCELLED whatever the adapter mapped the closed socket to (for example UNAVAILABLE, which is retryable, for
     * a body read that fails inside the SDK's JSON handling). The adapter's provider/model metadata is kept.
     */
    private static AiProviderException asCancelled(Throwable failure) {
        if (failure instanceof AiProviderException provider) {
            if (provider.category() == AiFailureCategory.CANCELLED) {
                return provider;
            }
            AiInvocationMetadata m = provider.metadata();
            return new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(m.providerId(),
                    m.modelId(), m.providerRequestId(), m.latency(), m.usage(), AiCompletionStatus.CANCELLED));
        }
        return new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(
                "unknown", null, null, Duration.ZERO, null, AiCompletionStatus.CANCELLED));
    }

    private static <T> T call(Callable<T> attempt) {
        try {
            return attempt.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Provider attempt failed", e);
        }
    }

    /** No provider was reached for this attempt: ADR 0019's "none/none" rather than an unknown provider. */
    private static AiProviderException notStarted() {
        return new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(
                "none", null, null, Duration.ZERO, null, AiCompletionStatus.CANCELLED));
    }

    /**
     * One provider attempt on its own virtual thread. {@link #cancel} interrupts the worker but does not complete the
     * result, so the caller still receives the attempt's real outcome and metadata. It counts as cancelled only when
     * the cancel was requested before the attempt finished and the attempt then failed; an attempt that completed
     * normally despite a late cancel is a completion.
     */
    static final class WorkerAttempt<T> implements Future<T> {
        private enum State { RUNNING, CANCEL_REQUESTED, FINISHED }

        private final CompletableFuture<T> result = new CompletableFuture<>();
        /**
         * The single point that decides the race: the worker publishing its outcome (RUNNING to FINISHED) and a
         * cancel (RUNNING to CANCEL_REQUESTED) are one CAS on the same state, so exactly one of them wins.
         */
        private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
        private final Thread worker;

        private WorkerAttempt(Callable<T> attempt) {
            worker = Thread.ofVirtual().unstarted(() -> {
                T value;
                try {
                    value = attempt.call();
                } catch (Throwable t) {
                    state.compareAndSet(State.RUNNING, State.FINISHED);
                    result.completeExceptionally(t);
                    return;
                }
                state.compareAndSet(State.RUNNING, State.FINISHED);
                result.complete(value);
            });
        }

        /** The cancel won the race against the attempt publishing its outcome. */
        boolean cancelWon() {
            return state.get() == State.CANCEL_REQUESTED;
        }

        static <T> WorkerAttempt<T> start(Callable<T> attempt) {
            WorkerAttempt<T> started = new WorkerAttempt<>(attempt);
            started.worker.start();
            return started;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!state.compareAndSet(State.RUNNING, State.CANCEL_REQUESTED)) {
                return false;
            }
            worker.interrupt();
            return true;
        }

        /** The cancel won the race and the attempt then did not complete normally. */
        @Override
        public boolean isCancelled() {
            return cancelWon() && result.isCompletedExceptionally();
        }

        @Override
        public boolean isDone() {
            return result.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return result.get();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }
    }
}
