package io.github.stevdrey.dokene.spike.cancellation;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.client.okhttp.OkHttpClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.ClientOptions;
import com.openai.core.RequestOptions;
import com.openai.core.http.HttpClient;
import com.openai.core.http.HttpRequest;
import com.openai.core.http.HttpResponse;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import io.github.stevdrey.dokene.ai.provider.openai.OpenAiProviderProperties;
import io.github.stevdrey.dokene.ai.provider.openai.OpenAiResponsesApiAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike evidence for Issue #180: does a cancellation actually close the HTTP call to the provider (not only map a
 * Java interrupt to {@code CANCELLED}), and does nothing retry afterwards? The "provider" is a raw socket that reads
 * the request and never answers; it observes the client closing the connection.
 */
@Tag("spike")
class OpenAiCallCancellationSpikeTest {

    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(4);
    private static final Duration PROMPT_CLOSE = Duration.ofMillis(1_000);
    private static final Duration RETRY_GRACE = Duration.ofMillis(750);
    private static final int RACE_ITERATIONS = 5_000;
    private static final Duration BODY_READ_SETTLE = Duration.ofMillis(200);

    private final RecommendationContext context = new RecommendationContext(
            new RecommendationContext.TrustedFacts(
                    LocalDate.of(2026, 9, 26), "DUE", List.of(TrustedFollowUpReason.DUE_TODAY), 30,
                    LocalDate.of(2026, 9, 26), true, List.of(Instant.parse("2026-09-01T12:00:00Z")),
                    List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP, SemanticAction.GENERAL_CHECK_IN)),
            new RecommendationContext.UntrustedText("Acme Corp", "Loyal customer notes", List.of("Widget Pro")));
    private final AiRecommendationRequest request =
            new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION, context, PROVIDER_TIMEOUT);

    private SilentProvider provider;

    @BeforeEach
    void startProvider() throws IOException {
        provider = new SilentProvider();
    }

    @AfterEach
    void stopProvider() throws IOException {
        provider.close();
    }

    @Test
    void interruptingTheBlockingSdkCallDoesNotCloseTheProviderSocketPromptly() throws Exception {
        OpenAiResponsesApiAdapter adapter = new OpenAiResponsesApiAdapter(standardClient(), properties());
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                adapter.recommend(request);
            } catch (Throwable t) {
                outcome.set(t);
            }
        });

        assertThat(provider.requestReceived.await(5, TimeUnit.SECONDS)).isTrue();
        // OkHttp honours an interrupt at its own checkpoints (e.g. while still writing the request); the case that
        // matters is a thread already blocked reading the response, so let it get there first.
        Thread.sleep(BODY_READ_SETTLE.toMillis());
        long cancelNanos = System.nanoTime();
        worker.interrupt();

        boolean closedPromptly = provider.closed.await(PROMPT_CLOSE.toMillis(), TimeUnit.MILLISECONDS);
        worker.join(PROVIDER_TIMEOUT.plusSeconds(2).toMillis());
        report("sync + Thread.interrupt()", cancelNanos, outcome.get());

        assertThat(closedPromptly)
                .as("a platform thread blocked in a socket read ignores the interrupt until the SDK timeout")
                .isFalse();
        assertThat(closeLatency(cancelNanos)).isGreaterThan(PROMPT_CLOSE);
        assertThat(provider.requests.get()).isEqualTo(1);
    }

    @Test
    void cancellingTheTopLevelAsyncSdkFutureDoesNotReachTheHttpCall() throws Exception {
        OpenAIClient client = standardClient();
        CompletableFuture<Response> call = client.async().responses().create(
                ResponseCreateParams.builder().model("gpt-test").input("ping").build(),
                RequestOptions.builder().timeout(PROVIDER_TIMEOUT).build());

        assertThat(provider.requestReceived.await(5, TimeUnit.SECONDS)).isTrue();
        long cancelNanos = System.nanoTime();
        call.cancel(true);

        boolean closedPromptly = provider.closed.await(PROMPT_CLOSE.toMillis(), TimeUnit.MILLISECONDS);
        report("async client + top-level future.cancel(true)", cancelNanos, null);

        assertThat(call).isCancelled();
        // The SDK derives the returned future with thenApply-style stages; cancelling a dependent stage does not
        // propagate to the inner HTTP future, so okhttp3.Call#cancel never runs and the provider keeps working.
        assertThat(closedPromptly).as("top-level async cancel leaves the provider socket open").isFalse();
        assertThat(provider.requests.get()).isEqualTo(1);
    }

    @Test
    void cancellingThroughTheDelegatingHttpClientClosesTheSocketAndMapsToCancelled() throws Exception {
        OpenAiResponsesApiAdapter adapter = new OpenAiResponsesApiAdapter(cancellableClient(), properties());
        AiCancellationHandle handle = new AiCancellationHandle();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                ScopedValue.where(AiCancellationHandle.CURRENT, handle).run(() -> adapter.recommend(request));
            } catch (Throwable t) {
                outcome.set(t);
            } finally {
                interruptedAfter.set(Thread.interrupted());
            }
        });

        assertThat(provider.requestReceived.await(5, TimeUnit.SECONDS)).isTrue();
        long cancelNanos = System.nanoTime();
        assertThat(handle.cancel()).isTrue();
        assertThat(handle.cancel()).as("a second cancel is a no-op").isFalse();

        boolean closedPromptly = provider.closed.await(PROMPT_CLOSE.toMillis(), TimeUnit.MILLISECONDS);
        worker.join(PROMPT_CLOSE.toMillis());
        report("delegating HttpClient + handle.cancel()", cancelNanos, outcome.get());

        assertThat(closedPromptly).as("cancel closes the provider socket").isTrue();
        // Measured from when the cancel was initiated, so a cancel() that blocks before closing cannot hide a miss.
        assertThat(closeLatency(cancelNanos)).isLessThanOrEqualTo(PROMPT_CLOSE);
        assertThat(worker.isAlive()).isFalse();
        assertThat(outcome.get()).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.category()).isEqualTo(AiFailureCategory.CANCELLED));
        // Finding: the adapter re-asserts the interrupt flag for a CancellationException. On a pooled request thread
        // that flag would break the blocking audit write that follows, so production must not leave it set.
        assertThat(interruptedAfter.get()).as("adapter leaves the worker interrupted").isTrue();
        Thread.sleep(RETRY_GRACE.toMillis());
        assertThat(provider.requests.get()).as("no retry after cancellation").isEqualTo(1);
    }

    @Test
    void cancelBeforeTheProviderCallStartsSendsNothing() throws Exception {
        OpenAiResponsesApiAdapter adapter = new OpenAiResponsesApiAdapter(cancellableClient(), properties());
        AiCancellationHandle handle = new AiCancellationHandle();
        handle.cancel();

        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                ScopedValue.where(AiCancellationHandle.CURRENT, handle).run(() -> adapter.recommend(request));
            } catch (Throwable t) {
                outcome.set(t);
            }
        });
        worker.join(PROMPT_CLOSE.toMillis());
        Thread.sleep(RETRY_GRACE.toMillis());

        assertThat(outcome.get()).isInstanceOfSatisfying(AiProviderException.class,
                e -> assertThat(e.category()).isEqualTo(AiFailureCategory.CANCELLED));
        assertThat(provider.connections.get()).isZero();
    }

    @Test
    void aCancelRacingTheStartOfTheHttpCallNeverLeavesARequestRunning() throws Exception {
        int prevented = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                CountingHttpClient delegate = new CountingHttpClient();
                CancellableHttpClient client = new CancellableHttpClient(delegate);
                AiCancellationHandle handle = new AiCancellationHandle();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> caller = pool.submit(() -> {
                    start.await();
                    try {
                        ScopedValue.where(AiCancellationHandle.CURRENT, handle)
                                .run(() -> client.execute(null, RequestOptions.none()));
                    } catch (CancellationException expected) {
                        // prevented before the start, or aborted after it
                    }
                    return null;
                });
                Future<?> canceller = pool.submit(() -> {
                    start.await();
                    handle.cancel();
                    return null;
                });
                start.countDown();
                canceller.get(5, TimeUnit.SECONDS);
                caller.get(5, TimeUnit.SECONDS);

                assertThat(handle.aborted()).isTrue();
                if (delegate.started.get() == 0) {
                    prevented++;
                } else {
                    assertThat(delegate.lastCall.get()).as("a started request is always cancelled").isCancelled();
                }
            }
        }
        System.out.printf("[spike-180] HttpClient cancel/start race: %d iterations, prevented %d, aborted after start %d%n",
                RACE_ITERATIONS, prevented, RACE_ITERATIONS - prevented);
    }

    @Test
    void theDelegatingHttpClientCannotCancelOnceTheBodyIsBeingRead() throws Exception {
        provider.stallMidBody = true;
        OpenAiResponsesApiAdapter adapter = new OpenAiResponsesApiAdapter(cancellableClient(), properties());
        AiCancellationHandle handle = new AiCancellationHandle();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try {
                ScopedValue.where(AiCancellationHandle.CURRENT, handle).run(() -> adapter.recommend(request));
            } catch (Throwable t) {
                outcome.set(t);
            } finally {
                Thread.interrupted();
            }
        });

        assertThat(provider.bodyStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(BODY_READ_SETTLE.toMillis());
        long cancelNanos = System.nanoTime();
        handle.cancel();

        boolean closedPromptly = provider.closed.await(PROMPT_CLOSE.toMillis(), TimeUnit.MILLISECONDS);
        worker.join(PROVIDER_TIMEOUT.plusSeconds(2).toMillis());
        report("delegating HttpClient + cancel while reading the body", cancelNanos, outcome.get());

        // The HTTP future completed when the headers arrived, so Call#cancel is no longer reachable.
        assertThat(closedPromptly).as("cancel during the body read leaves the provider socket open").isFalse();
        assertThat(handle.aborted()).isFalse();
    }

    @Test
    void aVirtualThreadWorkerClosesTheSocketWhileWaitingForHeaders() throws Exception {
        assertVirtualThreadWorkerClosesTheSocket("virtual-thread worker + cancel while waiting for headers",
                provider.requestReceived);
    }

    @Test
    void aVirtualThreadWorkerClosesTheSocketWhileReadingTheBody() throws Exception {
        provider.stallMidBody = true;
        assertVirtualThreadWorkerClosesTheSocket("virtual-thread worker + cancel while reading the body",
                provider.bodyStarted);
    }

    private void assertVirtualThreadWorkerClosesTheSocket(String path, CountDownLatch phaseReached) throws Exception {
        VirtualThreadCancellableProvider cancellable =
                new VirtualThreadCancellableProvider(new OpenAiResponsesApiAdapter(standardClient(), properties()));
        AiCancellationHandle handle = new AiCancellationHandle();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicReference<Boolean> requestThreadInterrupted = new AtomicReference<>();
        Thread requestThread = Thread.ofPlatform().start(() -> {
            try {
                ScopedValue.where(AiCancellationHandle.CURRENT, handle).run(() -> cancellable.recommend(request));
            } catch (Throwable t) {
                outcome.set(t);
            } finally {
                requestThreadInterrupted.set(Thread.interrupted());
            }
        });

        assertThat(phaseReached.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(BODY_READ_SETTLE.toMillis());
        long cancelNanos = System.nanoTime();
        handle.cancel();

        assertThat(provider.closed.await(PROMPT_CLOSE.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        requestThread.join(PROMPT_CLOSE.toMillis());
        report(path, cancelNanos, outcome.get());

        assertThat(closeLatency(cancelNanos)).isLessThanOrEqualTo(PROMPT_CLOSE);
        assertThat(requestThread.isAlive()).isFalse();
        assertThat(outcome.get()).isInstanceOfSatisfying(AiProviderException.class, e -> {
            assertThat(e.category()).isEqualTo(AiFailureCategory.CANCELLED);
            // The adapter's own exception surfaces, so telemetry keeps the provider/model tags (ADR 0019).
            assertThat(e.metadata().providerId()).isEqualTo("openai");
            assertThat(e.metadata().modelId()).isEqualTo("gpt-test");
        });
        assertThat(handle.aborted()).isTrue();
        assertThat(requestThreadInterrupted.get()).as("only the worker is interrupted").isFalse();
        Thread.sleep(RETRY_GRACE.toMillis());
        assertThat(provider.requests.get()).isEqualTo(1);
    }

    @Test
    void aWorkerCancelRacingCompletionKeepsTheRealOutcomeAndMetadata() throws Exception {
        AiRecommendationResponse generated = new AiRecommendationResponse(
                new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT, "Insufficient signal",
                        RecommendationConfidence.of(0.4)),
                new AiInvocationMetadata("fake", "fake-model", "req-1", Duration.ofMillis(1), null,
                        AiCompletionStatus.SUCCEEDED));
        int cancelled = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                CountDownLatch go = new CountDownLatch(1);
                VirtualThreadCancellableProvider cancellable = new VirtualThreadCancellableProvider(attempt -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AiProviderException(AiFailureCategory.CANCELLED, new AiInvocationMetadata(
                                "fake", "fake-model", null, Duration.ZERO, null, AiCompletionStatus.CANCELLED));
                    }
                    return generated;
                });
                AiCancellationHandle handle = new AiCancellationHandle();
                CountDownLatch started = new CountDownLatch(1);
                Future<Object> caller = pool.submit(() -> {
                    try {
                        return ScopedValue.where(AiCancellationHandle.CURRENT, handle).call(() -> {
                            started.countDown();
                            return cancellable.recommend(request);
                        });
                    } catch (AiProviderException e) {
                        return e;
                    }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> canceller = pool.submit(handle::cancel);
                go.countDown();
                canceller.get(5, TimeUnit.SECONDS);
                Object outcome = caller.get(5, TimeUnit.SECONDS);

                if (outcome instanceof AiProviderException failure) {
                    cancelled++;
                    assertThat(failure.category()).isEqualTo(AiFailureCategory.CANCELLED);
                    assertThat(failure.metadata().providerId()).isIn("fake", "none");
                    assertThat(handle.aborted()).isTrue();
                } else {
                    assertThat(outcome).isSameAs(generated);
                    assertThat(handle.aborted()).as("a completion is never reported as cancelled").isFalse();
                }
            }
        }
        System.out.printf("[spike-180] worker cancel/completion race: %d iterations, cancelled %d, completed %d%n",
                RACE_ITERATIONS, cancelled, RACE_ITERATIONS - cancelled);
    }

    @Test
    void aWorkerCancelRacingAFailedCompletionNeverRelabelsIt() throws Exception {
        int cancelled = 0;
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int i = 0; i < RACE_ITERATIONS; i++) {
                CountDownLatch go = new CountDownLatch(1);
                VirtualThreadCancellableProvider cancellable = new VirtualThreadCancellableProvider(attempt -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // Fails with a real provider outcome whether or not the interrupt arrived.
                    throw new AiProviderException(AiFailureCategory.UNAVAILABLE, new AiInvocationMetadata(
                            "fake", "fake-model", null, Duration.ZERO, null, AiCompletionStatus.FAILED));
                });
                AiCancellationHandle handle = new AiCancellationHandle();
                CountDownLatch started = new CountDownLatch(1);
                Future<AiProviderException> caller = pool.submit(() -> {
                    try {
                        ScopedValue.where(AiCancellationHandle.CURRENT, handle).call(() -> {
                            started.countDown();
                            return cancellable.recommend(request);
                        });
                        throw new AssertionError("the delegate always fails");
                    } catch (AiProviderException e) {
                        return e;
                    }
                });
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> canceller = pool.submit(handle::cancel);
                go.countDown();
                canceller.get(5, TimeUnit.SECONDS);
                AiProviderException failure = caller.get(5, TimeUnit.SECONDS);

                if (failure.category() == AiFailureCategory.CANCELLED) {
                    cancelled++;
                    assertThat(handle.aborted()).isTrue();
                } else {
                    assertThat(failure.category()).isEqualTo(AiFailureCategory.UNAVAILABLE);
                    assertThat(handle.aborted()).as("a real failure is never relabelled as cancelled").isFalse();
                }
                assertThat(failure.metadata().providerId()).isIn("fake", "none");
            }
        }
        System.out.printf("[spike-180] worker cancel/failure race: %d iterations, cancelled %d, real failure %d%n",
                RACE_ITERATIONS, cancelled, RACE_ITERATIONS - cancelled);
    }

    private OpenAiProviderProperties properties() {
        return new OpenAiProviderProperties("test-api-key", "gpt-test", provider.baseUrl(), PROVIDER_TIMEOUT, 0);
    }

    private OpenAIClient standardClient() {
        return OpenAIOkHttpClient.builder()
                .apiKey("test-api-key")
                .baseUrl(provider.baseUrl())
                .timeout(PROVIDER_TIMEOUT)
                .maxRetries(0)
                .build();
    }

    private OpenAIClient cancellableClient() {
        OkHttpClient okHttp = OkHttpClient.builder().timeout(PROVIDER_TIMEOUT).build();
        return new OpenAIClientImpl(ClientOptions.builder()
                .httpClient(new CancellableHttpClient(okHttp))
                .apiKey("test-api-key")
                .baseUrl(provider.baseUrl())
                .timeout(PROVIDER_TIMEOUT)
                .maxRetries(0)
                .build());
    }

    private Duration closeLatency(long cancelNanos) {
        long closeNanos = provider.closedAtNanos.get();
        assertThat(closeNanos).as("the provider observed the socket close").isNotZero();
        return Duration.ofNanos(closeNanos - cancelNanos);
    }

    private void report(String path, long cancelNanos, Throwable outcome) {
        long closeNanos = provider.closedAtNanos.get();
        String closeAfter = closeNanos == 0 ? "not closed" : Duration.ofNanos(closeNanos - cancelNanos).toMillis() + " ms";
        String result = outcome instanceof AiProviderException e ? "AiProviderException(" + e.category() + ")"
                : outcome == null ? "none" : outcome.getClass().getSimpleName();
        System.out.printf("[spike-180] %s: socket closed after %s; requests=%d; outcome=%s%n",
                path, closeAfter, provider.requests.get(), result);
    }

    /** Stands in for the SDK OkHttp client: counts started requests, which only finish when cancelled. */
    private static final class CountingHttpClient implements HttpClient {
        final AtomicInteger started = new AtomicInteger();
        final AtomicReference<CompletableFuture<HttpResponse>> lastCall = new AtomicReference<>();

        @Override
        public HttpResponse execute(HttpRequest request, RequestOptions requestOptions) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<HttpResponse> executeAsync(HttpRequest request, RequestOptions requestOptions) {
            started.incrementAndGet();
            CompletableFuture<HttpResponse> call = new CompletableFuture<>();
            lastCall.set(call);
            return call;
        }

        @Override
        public void close() {
        }
    }

    /** Accepts connections, reads the request, never answers and records when the client closes the socket. */
    private static final class SilentProvider implements AutoCloseable {
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger requests = new AtomicInteger();
        final CountDownLatch requestReceived = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicLong closedAtNanos = new AtomicLong();
        final CountDownLatch bodyStarted = new CountDownLatch(1);
        /** When set, answers 200 with headers and the start of a large body, then stalls mid-body. */
        volatile boolean stallMidBody;
        private final ServerSocket server;

        SilentProvider() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(this::acceptLoop);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/v1";
        }

        private void acceptLoop() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    connections.incrementAndGet();
                    Thread.ofVirtual().start(() -> hold(socket));
                } catch (IOException e) {
                    return;
                }
            }
        }

        private void hold(Socket socket) {
            byte[] buffer = new byte[8192];
            boolean counted = false;
            try (socket; InputStream in = socket.getInputStream()) {
                while (in.read(buffer) != -1) {
                    if (!counted) {
                        counted = true;
                        requests.incrementAndGet();
                        requestReceived.countDown();
                        if (stallMidBody) {
                            OutputStream out = socket.getOutputStream();
                            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                                    + "Content-Length: 1000000\r\n\r\n{\"id\":\"resp_1\",\"object\":\"response\",")
                                    .getBytes(StandardCharsets.US_ASCII));
                            out.flush();
                            bodyStarted.countDown();
                        }
                    }
                }
            } catch (IOException ignored) {
                // A reset is also a close observed by the provider.
            }
            closedAtNanos.compareAndSet(0, System.nanoTime());
            closed.countDown();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
