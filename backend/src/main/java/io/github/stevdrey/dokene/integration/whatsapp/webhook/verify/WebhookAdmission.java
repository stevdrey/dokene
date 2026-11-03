// ABOUTME: Bounds how many webhook callbacks are read and processed at once (issue 306): a permit or an immediate no.
// ABOUTME: Never waits or queues, so a flood costs at most the bound times the body limit in heap and never piles up.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WebhookAdmission {
    private final Semaphore permits;

    /** {@code maxConcurrent} is validated against the property's range by the configuration before it reaches here. */
    public WebhookAdmission(int maxConcurrent) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("The concurrency bound must be at least 1");
        }
        this.permits = new Semaphore(maxConcurrent);
    }

    /** A permit to hold for the whole request, or empty when every permit is taken. */
    public Optional<Permit> tryAdmit() {
        return permits.tryAcquire() ? Optional.of(new Permit()) : Optional.empty();
    }

    /** Returns its permit on the first close only, so a double close can never raise the bound. */
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Permit() {
        }

        @Override
        public void close() {
            if (open.compareAndSet(true, false)) {
                permits.release();
            }
        }
    }
}
