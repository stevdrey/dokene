// ABOUTME: Replays the delivery reports the local provider planned, after the completing transaction committed.
// ABOUTME: One daemon thread, each report under the provider context of the command's tenant, never retried.
package io.github.stevdrey.dokene.integration.local;

import io.github.stevdrey.dokene.integration.ProviderReportExecution;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusResult;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.messaging.application.SendAttemptCompleted;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class LocalDeliveryScheduler {
    static final String DROPPED = "dokene.messaging.local.delivery-dropped";
    private static final Logger log = LoggerFactory.getLogger(LocalDeliveryScheduler.class);

    private final DeliveryStatusSink sink;
    private final ProviderReportExecution execution;
    private final Duration delay;
    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<String, Planned> planned = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "local-messaging-delivery");
        thread.setDaemon(true);
        return thread;
    });

    public LocalDeliveryScheduler(DeliveryStatusSink sink, ProviderReportExecution execution,
            LocalMessagingProperties properties, MeterRegistry registry, Clock clock) {
        this.sink = Objects.requireNonNull(sink, "Delivery sink is required");
        this.execution = Objects.requireNonNull(execution, "Provider execution is required");
        this.delay = Objects.requireNonNull(properties, "Local messaging properties are required").deliveryDelay();
        this.registry = registry;
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** Remembers the reports to replay once the attempt that produced this id has committed. */
    void plan(String providerMessageId, UUID tenantId, List<DeliveryStatus> reports) {
        planned.put(providerMessageId, new Planned(tenantId, List.copyOf(reports)));
    }

    /** Runs only after phase 3 committed; a rolled back completion leaves the plan unused. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttemptCompleted(SendAttemptCompleted event) {
        Optional<String> providerMessageId = event.providerMessageId();
        if (providerMessageId.isEmpty()) {
            return;
        }
        Planned plan = planned.remove(providerMessageId.get());
        if (plan == null || !plan.tenantId().equals(event.tenantId())) {
            return;
        }
        for (int i = 0; i < plan.reports().size(); i++) {
            DeliveryStatus status = plan.reports().get(i);
            long wait = delay.multipliedBy(i + 1L).toMillis();
            try {
                executor.schedule(() -> report(providerMessageId.get(), plan.tenantId(), status, event.messageId()),
                        wait, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException rejected) {
                dropped("rejected", event.messageId(), rejected);
            }
        }
    }

    private void report(String providerMessageId, UUID tenantId, DeliveryStatus status, UUID messageId) {
        try {
            DeliveryStatusResult result = execution.call(tenantId, () -> sink.apply(
                    new DeliveryStatusReport(providerMessageId, status, clock.instant(), Optional.empty())));
            switch (result) {
                case DeliveryStatusResult.Applied applied -> log.info(
                        "Local delivery report applied; messageId={}, tenantId={}, statusFrom={}, statusTo={}",
                        messageId, tenantId, applied.from(), applied.to());
                case DeliveryStatusResult.Ignored ignored -> count("ignored");
                case DeliveryStatusResult.NotFound notFound -> count("not_found");
            }
        } catch (MessagingRefusedException refused) {
            dropped("refused", messageId, refused);
        } catch (RuntimeException exception) {
            dropped("error", messageId, exception);
        }
    }

    private void dropped(String reason, UUID messageId, Exception exception) {
        log.warn("Local delivery report dropped; reason={}, messageId={}, exception={}", reason, messageId,
                exception.getClass().getName());
        count(reason);
    }

    private void count(String reason) {
        if (registry != null) {
            registry.counter(DROPPED, "reason", reason).increment();
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private record Planned(UUID tenantId, List<DeliveryStatus> reports) {
    }
}
