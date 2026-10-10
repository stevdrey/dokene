// ABOUTME: Unit tests for the after commit replay of planned delivery reports (spec 0003 AC-7).
// ABOUTME: A fake execution runs the operation inline; the sink is a mock the test awaits with a latch.
package io.github.stevdrey.dokene.integration.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.integration.ProviderReportExecution;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusResult;
import io.github.stevdrey.dokene.messaging.application.DeliveryStatusSink;
import io.github.stevdrey.dokene.messaging.application.SendAttemptCompleted;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.ScopedValueProviderContextProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LocalDeliverySchedulerTest {
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final DeliveryStatusSink sink = mock(DeliveryStatusSink.class);
    private final ProviderContextProvider providerContexts = new ScopedValueProviderContextProvider();
    private final ProviderReportExecution execution = new ProviderReportExecution(providerContexts,
            new AuditExecutionContext());
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LocalDeliveryScheduler scheduler = new LocalDeliveryScheduler(sink, execution,
            new LocalMessagingProperties(Duration.ZERO), registry, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID tenantId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    @AfterEach
    void shutdown() {
        scheduler.shutdown();
    }

    @Test
    void replaysThePlannedReportsInOrderUnderTheProviderContextAfterTheAttemptCommitted() throws Exception {
        List<DeliveryStatusReport> seen = new ArrayList<>();
        List<Boolean> underContext = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        when(sink.apply(any())).thenAnswer(invocation -> {
            synchronized (seen) {
                seen.add(invocation.getArgument(0));
                underContext.add(providerContexts.current()
                        .map(context -> context.tenantId().value().equals(tenantId)).orElse(false));
            }
            latch.countDown();
            return new DeliveryStatusResult.Applied(MessageStatus.SENT, MessageStatus.DELIVERED);
        });
        scheduler.plan("local-1", tenantId, List.of(DeliveryStatus.DELIVERED, DeliveryStatus.READ));

        scheduler.onAttemptCompleted(new SendAttemptCompleted(tenantId, messageId, Optional.of("local-1"),
                SendAttemptOutcome.ACCEPTED));

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).extracting(DeliveryStatusReport::status)
                .containsExactly(DeliveryStatus.DELIVERED, DeliveryStatus.READ);
        assertThat(seen).allSatisfy(report -> {
            assertThat(report.providerMessageId()).isEqualTo("local-1");
            assertThat(report.occurredAt()).isEqualTo(NOW);
        });
        assertThat(underContext).containsExactly(true, true);
        assertThat(providerContexts.current()).isEmpty();
    }

    @Test
    void ignoresCompletionsWithoutAnIdOrWithoutAPlanOrForAnotherTenant() throws Exception {
        scheduler.plan("local-2", UUID.randomUUID(), List.of(DeliveryStatus.DELIVERED));

        scheduler.onAttemptCompleted(new SendAttemptCompleted(tenantId, messageId, Optional.empty(),
                SendAttemptOutcome.OUTCOME_UNKNOWN));
        scheduler.onAttemptCompleted(new SendAttemptCompleted(tenantId, messageId, Optional.of("local-none"),
                SendAttemptOutcome.ACCEPTED));
        scheduler.onAttemptCompleted(new SendAttemptCompleted(tenantId, messageId, Optional.of("local-2"),
                SendAttemptOutcome.ACCEPTED));

        Thread.sleep(100);
        verify(sink, never()).apply(any());
    }

    @Test
    void aRefusedOrFailedReportIsCountedAndNeverRetried() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        when(sink.apply(any())).thenAnswer(invocation -> {
            latch.countDown();
            DeliveryStatusReport report = invocation.getArgument(0);
            return switch (report.providerMessageId()) {
                case "local-refused" -> throw new MessagingRefusedException(MessagingErrorCode.FORBIDDEN);
                case "local-missing" -> new DeliveryStatusResult.NotFound();
                default -> throw new IllegalStateException("boom");
            };
        });
        for (String id : List.of("local-refused", "local-missing", "local-error")) {
            scheduler.plan(id, tenantId, List.of(DeliveryStatus.DELIVERED));
            scheduler.onAttemptCompleted(new SendAttemptCompleted(tenantId, messageId, Optional.of(id),
                    SendAttemptOutcome.ACCEPTED));
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(50);
        assertThat(registry.counter(LocalDeliveryScheduler.DROPPED, "reason", "refused").count()).isEqualTo(1.0);
        assertThat(registry.counter(LocalDeliveryScheduler.DROPPED, "reason", "not_found").count()).isEqualTo(1.0);
        assertThat(registry.counter(LocalDeliveryScheduler.DROPPED, "reason", "error").count()).isEqualTo(1.0);
        verify(sink, org.mockito.Mockito.times(3)).apply(any());
    }

    @Test
    void aTaskScheduledAfterShutdownIsDroppedAndCounted() {
        scheduler.plan("local-late", tenantId, List.of(DeliveryStatus.DELIVERED));
        scheduler.shutdown();

        scheduler.onAttemptCompleted(new SendAttemptCompleted(tenantId, messageId, Optional.of("local-late"),
                SendAttemptOutcome.ACCEPTED));

        assertThat(registry.counter(LocalDeliveryScheduler.DROPPED, "reason", "rejected").count()).isEqualTo(1.0);
    }
}
