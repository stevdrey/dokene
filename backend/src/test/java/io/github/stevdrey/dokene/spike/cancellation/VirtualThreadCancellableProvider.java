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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

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
        AiCancellationHandle handle = CancellableHttpClient.HANDLE.isBound() ? CancellableHttpClient.HANDLE.get() : null;
        if (handle == null) {
            return call(attempt);
        }
        Future<T> worker;
        try {
            worker = handle.start(() -> workers.submit(attempt));
        } catch (CancellationException e) {
            throw cancelled();
        }
        try {
            return worker.get();
        } catch (CancellationException e) {
            throw cancelled();
        } catch (InterruptedException e) {
            worker.cancel(true);
            Thread.currentThread().interrupt();
            throw cancelled();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Provider attempt failed", e.getCause());
        }
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

    private static AiProviderException cancelled() {
        return new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(
                "unknown", null, null, Duration.ZERO, null, AiCompletionStatus.CANCELLED));
    }
}
