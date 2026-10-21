// ABOUTME: Records parsed webhook statuses in the durable inbox before the route acknowledges (issue 139, ADR 0030).
// ABOUTME: The tenant comes only from the verified mapping resolver; unknown numbers and duplicates store nothing.
package io.github.stevdrey.dokene.integration.whatsapp.webhook;

import io.github.stevdrey.dokene.integration.application.VerifiedPhoneNumberMappings;
import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEvent.Provider;
import io.github.stevdrey.dokene.integration.application.WebhookInboxRepository;
import io.github.stevdrey.dokene.integration.application.WebhookInboxRepository.DueEvent;
import io.github.stevdrey.dokene.integration.application.WebhookInboxRepository.InboxEvent;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.ParsedWebhook.StatusUpdate;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookTelemetry;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookTelemetry.DropReason;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each status is inserted in its own transaction under the resolved tenant. Any failure propagates, so the route does
 * not acknowledge and Meta redelivers the batch; rows already committed then deduplicate. Once every status is
 * recorded, the new rows are applied inline as a best effort: a failure there leaves them PENDING for the sweeper and
 * never turns an acknowledged batch into a provider retry.
 */
@Service
public class WebhookIngestionService {
    private static final Provider PROVIDER = Provider.META_WHATSAPP;

    private final VerifiedPhoneNumberMappings mappings;
    private final WebhookInboxRepository inbox;
    private final WebhookInboxProcessor processor;
    private final TenantContextProvider tenantContexts;
    private final WebhookTelemetry telemetry;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public WebhookIngestionService(VerifiedPhoneNumberMappings mappings, WebhookInboxRepository inbox,
            WebhookInboxProcessor processor, TenantContextProvider tenantContexts, WebhookTelemetry telemetry,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.mappings = Objects.requireNonNull(mappings);
        this.inbox = Objects.requireNonNull(inbox);
        this.processor = Objects.requireNonNull(processor);
        this.tenantContexts = Objects.requireNonNull(tenantContexts);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns once every status is durably recorded or dropped; the caller may acknowledge after that. */
    public void ingest(ParsedWebhook parsed) {
        for (int i = 0; i < parsed.unsupportedCount(); i++) {
            telemetry.dropped(DropReason.UNSUPPORTED_EVENT);
        }
        Map<String, Optional<TenantId>> tenants = new HashMap<>();
        List<DueEvent> recorded = new ArrayList<>();
        for (StatusUpdate update : parsed.updates()) {
            Optional<TenantId> tenant = tenants.computeIfAbsent(update.phoneNumberId(), mappings::tenantFor);
            if (tenant.isEmpty()) {
                // Unknown, unverified or disabled mapping: the same 200, nothing stored (P3-HOOK-08, #210 gate).
                telemetry.dropped(DropReason.UNKNOWN_PHONE_NUMBER);
                continue;
            }
            Instant now = clock.instant();
            InboxEvent event = new InboxEvent(UUID.randomUUID(), tenant.get(), PROVIDER,
                    eventId(update.providerMessageId(), update.status()), update.providerMessageId(),
                    update.status(), update.occurredAt(), update.failure(), 0, now);
            boolean inserted = Boolean.TRUE.equals(tenantContexts.callWithTenantId(tenant.get(),
                    () -> transactions.execute(status -> inbox.insertPending(event))));
            if (inserted) {
                recorded.add(new DueEvent(tenant.get(), event.id()));
            } else {
                telemetry.dropped(DropReason.DUPLICATE);
            }
        }
        processor.process(recorded);
    }

    /**
     * Meta statuses carry no event id. One message reaches each delivery state once, so (message, state) is the
     * identity: a byte-identical redelivery and a re-sent state with another timestamp both deduplicate. The hash
     * keeps the id fixed length and keeps the provider message id out of the key.
     */
    static String eventId(String providerMessageId, DeliveryStatus status) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((PROVIDER.name() + '|' + providerMessageId + '|' + status.name())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
