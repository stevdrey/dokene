package io.github.stevdrey.dokene.spike.cancellation;

import com.openai.core.RequestOptions;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Spike prototype (Issue #180), test sources only. Keeps the blocking SDK call shape for the adapter, but runs the
 * HTTP exchange through the SDK's async OkHttp path, whose future cancels the underlying {@code okhttp3.Call} (and
 * so closes the provider socket) when it is cancelled. The handle is bound per invocation with a {@link ScopedValue}.
 */
final class CancellableHttpClient implements HttpClient {
    static final ScopedValue<AiCancellationHandle> HANDLE = ScopedValue.newInstance();

    private final HttpClient delegate;

    CancellableHttpClient(HttpClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "Delegate HTTP client is required");
    }

    @Override
    public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
        if (!HANDLE.isBound()) {
            return delegate.execute(request, requestOptions);
        }
        AiCancellationHandle handle = HANDLE.get();
        if (handle.isCancelled()) {
            throw new CancellationException("AI invocation cancelled before the provider call started");
        }
        CompletableFuture<HttpResponse> call = delegate.executeAsync(request, requestOptions);
        handle.attach(call);
        try {
            return call.get();
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new CancellationException("AI invocation interrupted");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Provider call failed", e.getCause());
        }
    }

    @Override
    public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
        return delegate.executeAsync(request, requestOptions);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
