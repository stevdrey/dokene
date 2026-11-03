// ABOUTME: Proves the webhook admission bound of issue 306: no permit past the bound, a close returns exactly one,
// ABOUTME: and many threads racing for permits never hold more than the bound at once.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookAdmission.Permit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebhookAdmissionTest {
    private static final int BOUND = 3;

    private final WebhookAdmission admission = new WebhookAdmission(BOUND);

    @Test
    void noPermitIsGivenPastTheBoundUntilOneIsClosed() {
        List<Permit> held = new ArrayList<>();
        for (int index = 0; index < BOUND; index++) {
            held.add(admission.tryAdmit().orElseThrow());
        }

        assertThat(admission.tryAdmit()).isEmpty();

        held.getFirst().close();
        assertThat(admission.tryAdmit()).isPresent();
        assertThat(admission.tryAdmit()).isEmpty();
    }

    @Test
    void closingAPermitTwiceReturnsItOnce() {
        Permit permit = admission.tryAdmit().orElseThrow();

        permit.close();
        permit.close();

        assertThat(availablePermits()).isEqualTo(BOUND);
    }

    @Test
    void racingThreadsNeverHoldMoreThanTheBound() throws Exception {
        int threads = 32;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(threads);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int index = 0; index < threads; index++) {
                pool.submit(() -> {
                    start.await();
                    Optional<Permit> admitted = admission.tryAdmit();
                    attempted.countDown();
                    if (admitted.isEmpty()) {
                        refused.incrementAndGet();
                        return null;
                    }
                    try (Permit _ = admitted.get()) {
                        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        release.await();
                        inFlight.decrementAndGet();
                    }
                    return null;
                });
            }
            start.countDown();
            assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
        }

        assertThat(peak.get()).isEqualTo(BOUND);
        assertThat(refused.get()).isEqualTo(threads - BOUND);
        assertThat(availablePermits()).isEqualTo(BOUND);
    }

    /** Counts free permits through the public API only, and returns every one it took. */
    private int availablePermits() {
        List<Permit> taken = new ArrayList<>();
        for (Optional<Permit> next = admission.tryAdmit(); next.isPresent(); next = admission.tryAdmit()) {
            taken.add(next.get());
        }
        taken.forEach(Permit::close);
        return taken.size();
    }

    @Test
    void theBoundMustBeAtLeastOne() {
        assertThatThrownBy(() -> new WebhookAdmission(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
