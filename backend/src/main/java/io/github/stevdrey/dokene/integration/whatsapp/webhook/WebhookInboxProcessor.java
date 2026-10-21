// ABOUTME: Applies PENDING inbox rows (issue 139, ADR 0030): one transaction per row under a ProviderContext, a bounded
// ABOUTME: park for reports whose message is not found yet, retries after failures, and the retention purge.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEvent;
import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEventHandler;
import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEventHandler.Handling;
import io.github.stevdrey.dokene.integration.application.WebhookInboxRepository;
import io.github.stevdrey.dokene.integration.application.WebhookInboxRepository.DueEvent;
import io.github.stevdrey.dokene.integration.application.WebhookInboxRepository.InboxEvent;
import io.github.stevdrey.dokene.integration.application.WebhookInboxRepository.InboxState;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookTelemetry;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookTelemetry.DropReason;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A row is applied under a trusted {@link ProviderContext} for the tenant stored with it, which the ingestion resolved
 * from the verified mapping only; there is never a member behind it. The inbox update, the message transition, its
 * event row and the provider-attributed audit commit in one transaction, so an audit outage rolls all of them back and
 * leaves the row PENDING for a later run: work that was acknowledged is never lost.
 *
 * <p>A report whose provider message id matches no message of the tenant is parked: it may have arrived before the
 * dispatch committed that id (T8). It is retried with backoff until the park window ends, then becomes UNMATCHED with
 * the provider message id cleared, and is counted as dropped. Nothing here can create, approve or send a message.
 */
@Service
@EnableConfigurationProperties(WebhookInboxProperties.class)
public class WebhookInboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(WebhookInboxProcessor.class);
    static final Duration RETRY_BASE = Duration.ofSeconds(30);
    static final Duration RETRY_CAP = Duration.ofMinutes(5);
    private static final int PURGE_BATCH = 1000;

    private final WebhookInboxRepository inbox;
    private final VerifiedWebhookEventHandler handler;
    private final ProviderContextProvider providerContexts;
    private final TenantContextProvider tenantContexts;
    private final AuditExecutionContext auditExecution;
    private final WebhookTelemetry telemetry;
    private final WebhookInboxProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public WebhookInboxProcessor(WebhookInboxRepository inbox, VerifiedWebhookEventHandler handler,
            ProviderContextProvider providerContexts, TenantContextProvider tenantContexts,
            AuditExecutionContext auditExecution, WebhookTelemetry telemetry, WebhookInboxProperties properties,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.inbox = Objects.requireNonNull(inbox);
        this.handler = Objects.requireNonNull(handler);
        this.providerContexts = Objects.requireNonNull(providerContexts);
        this.tenantContexts = Objects.requireNonNull(tenantContexts);
        this.auditExecution = Objects.requireNonNull(auditExecution);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.properties = Objects.requireNonNull(properties);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
    }

    /** Scheduling is on application wide; the enabled property is the only switch for the sweeper. */
    @Scheduled(fixedDelayString = "${dokene.integration.whatsapp.webhook.inbox.interval:PT30S}",
            initialDelayString = "${dokene.integration.whatsapp.webhook.inbox.interval:PT30S}")
    void scheduledRun() {
        if (!properties.enabled()) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException exception) {
            log.error("Webhook inbox run failed; errorType={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * One bounded run over due PENDING rows, then the retention purge. Returns the rows processed to a terminal state,
     * or 0 without doing anything while another instance runs.
     */
    public int sweep() {
        if (tenantContexts.currentTenantId().isPresent() || providerContexts.current().isPresent()) {
            throw new IllegalStateException("Webhook inbox sweep must start without a tenant scope");
        }
        return inbox.runAsSoleSweeper(() -> {
            Instant now = clock.instant();
            List<DueEvent> due = inbox.findDue(now, properties.batchSize());
            int settled = process(due);
            int purged = inbox.purgeProcessedBefore(now.minus(properties.retention()), PURGE_BATCH);
            if (!due.isEmpty() || purged > 0) {
                log.info("Webhook inbox run; due={}, settled={}, purged={}", due.size(), settled, purged);
            }
            return settled;
        }).orElseGet(() -> {
            log.debug("Webhook inbox run skipped; another instance is running");
            return 0;
        });
    }

    /** Processes each row on its own; a failure leaves that row PENDING and never stops the rest. */
    public int process(List<DueEvent> events) {
        int settled = 0;
        for (DueEvent event : events) {
            try {
                if (processOne(event.tenantId(), event.id())) {
                    settled++;
                }
            } catch (RuntimeException exception) {
                log.warn("Webhook event kept pending after a failure; eventId={}, tenantId={}, errorType={}",
                        event.id(), event.tenantId().value(), exception.getClass().getSimpleName());
                deferQuietly(event);
            }
        }
        return settled;
    }

    private boolean processOne(TenantId tenantId, UUID id) {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> providerContexts.callWithContext(new ProviderContext(tenantId),
                        () -> tenantContexts.callWithTenantId(tenantId,
                                () -> Boolean.TRUE.equals(transactions.execute(status -> apply(tenantId, id))))));
    }

    private boolean apply(TenantId tenantId, UUID id) {
        InboxEvent event = inbox.lockPending(tenantId, id).orElse(null);
        if (event == null) {
            return false;
        }
        Instant now = clock.instant();
        DeliveryStatusReport report = new DeliveryStatusReport(event.providerMessageId(), event.status(),
                event.occurredAt(), event.failure());
        Handling handling = handler.handle(new VerifiedWebhookEvent(event.provider(), event.providerEventId(),
                event.tenantId(), report));
        switch (handling) {
            case APPLIED -> inbox.markProcessed(tenantId, id, InboxState.APPLIED, now);
            case IGNORED -> inbox.markProcessed(tenantId, id, InboxState.IGNORED, now);
            case NOT_FOUND -> {
                if (now.isBefore(event.receivedAt().plus(properties.parkWindow()))) {
                    inbox.reschedule(tenantId, id, now, RETRY_BASE, RETRY_CAP);
                    return false;
                }
                inbox.markUnmatched(tenantId, id, now);
                telemetry.dropped(DropReason.UNMATCHED_MESSAGE);
                log.info("Webhook event unmatched after the park window; eventId={}, tenantId={}", id,
                        tenantId.value());
            }
        }
        return true;
    }

    /** Backs a failed row off in its own transaction; if even that fails, the row is simply due again. */
    private void deferQuietly(DueEvent event) {
        try {
            tenantContexts.callWithTenantId(event.tenantId(), () -> transactions.execute(status -> {
                inbox.reschedule(event.tenantId(), event.id(), clock.instant(), RETRY_BASE, RETRY_CAP);
                return null;
            }));
        } catch (RuntimeException exception) {
            log.warn("Webhook event backoff not recorded; eventId={}, errorType={}", event.id(),
                    exception.getClass().getSimpleName());
        }
    }
}
