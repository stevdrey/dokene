// ABOUTME: Bounded, redirect-free GET and POST against the configured Graph API base URL, shared by the WhatsApp adapters.
// ABOUTME: One caller-owned deadline covers headers and body; the body is capped while streaming; the token stays here.
package io.github.stevdrey.dokene.integration.whatsapp;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class GraphApiHttp {
    /** Hard cap on the bytes read from the provider; enforced while streaming, never after buffering. */
    static final int MAX_BODY_BYTES = 262_144;
    /** Graph cursors are opaque base64-like tokens; anything else is not sent back. */
    static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9_=+/-]{1,512}$");

    private final GraphApiProperties properties;
    private final HttpClient http;

    GraphApiHttp(GraphApiProperties properties) {
        this.properties = Objects.requireNonNull(properties, "Properties are required");
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** A {@link System#nanoTime()} deadline for one whole operation, however many pages it reads. */
    long deadline() {
        return System.nanoTime() + properties.timeout().toNanos();
    }

    /** The configured base URL followed by {@code pathAndQuery}, which the caller has already encoded. */
    URI uri(String pathAndQuery) {
        return URI.create(properties.baseUrl().toString().replaceAll("/+$", "") + "/" + pathAndQuery);
    }

    /** The status and, for a 200 within the size and time bounds, the body bytes. */
    record Reply(int status, byte[] body) {
    }

    /** Status 0 when the deadline already passed; throws on transport failure, which callers map to unavailable. */
    Reply get(URI uri, char[] token, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return new Reply(0, null);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("Authorization", "Bearer " + new String(token)).header("Accept", "application/json")
                    .GET().build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream stream = response.body();
            try {
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(0);
                if (response.statusCode() != 200 || declared > MAX_BODY_BYTES) {
                    return new Reply(response.statusCode(), null);
                }
                return new Reply(200, readBounded(stream, deadline));
            } finally {
                stream.close(); // also cancels the exchange when the body was not fully read
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Provider request failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider request interrupted");
        }
    }

    /** The outcome of one POST, which says whether the request may have reached the provider. */
    sealed interface PostOutcome {
    }

    /** The request provably never left: the deadline had passed or no connection was established. */
    record NotSent() implements PostOutcome {
    }

    /** The provider answered; {@code body} is null when it exceeded the size or time bound. */
    record Responded(int status, byte[] body) implements PostOutcome {
    }

    /** The request may have been received but no status arrived: a timeout or I/O failure after sending. */
    record Unknown() implements PostOutcome {
    }

    /**
     * Sends {@code json} once and never retries; the body of any status is read within the same bounds as
     * {@link #get}. Never throws for transport failures: they are classified by whether the request could have left.
     */
    PostOutcome post(URI uri, char[] token, byte[] json, long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return new NotSent();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining))
                    .header("Authorization", "Bearer " + new String(token)).header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json)).build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream stream = response.body();
            try {
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(0);
                byte[] body = declared > MAX_BODY_BYTES ? null : readBounded(stream, deadline);
                return new Responded(response.statusCode(), body);
            } finally {
                stream.close(); // also cancels the exchange when the body was not fully read
            }
        } catch (HttpConnectTimeoutException | ConnectException | IllegalArgumentException notConnected) {
            // An unusable request is refused by the client before anything is written.
            return new NotSent();
        } catch (IOException afterSending) {
            return new Unknown();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Unknown();
        }
    }

    /**
     * Reads at most {@code MAX_BODY_BYTES} before {@code deadline} (nanoTime), so neither a huge or endless body nor a
     * slow drip can exhaust the heap or hold the caller. Returns null when either bound is exceeded.
     */
    private byte[] readBounded(InputStream stream, long deadline) throws InterruptedException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return null;
        }
        CompletableFuture<byte[]> read = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                byte[] bytes = stream.readNBytes(MAX_BODY_BYTES + 1);
                read.complete(bytes.length > MAX_BODY_BYTES ? null : bytes);
            } catch (IOException | RuntimeException failure) {
                read.completeExceptionally(failure);
            }
        });
        try {
            return read.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException failure) {
            return null;
        }
    }
}
