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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The statuses of each resolved tenant are inserted in one transaction under that tenant. Any failure propagates, so
 * the route does not acknowledge and Meta redelivers the batch; groups already committed then deduplicate. Once every
 * status is recorded, up to the inline limit of the new rows are applied inline as a best effort: a failure there
 * leaves them PENDING for the sweeper and never turns an acknowledged batch into a provider retry.
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
    private final WebhookInboxProperties properties;
    private final Clock clock;

    public WebhookIngestionService(VerifiedPhoneNumberMappings mappings, WebhookInboxRepository inbox,
            WebhookInboxProcessor processor, TenantContextProvider tenantContexts, WebhookTelemetry telemetry,
            WebhookInboxProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.mappings = Objects.requireNonNull(mappings);
        this.inbox = Objects.requireNonNull(inbox);
        this.processor = Objects.requireNonNull(processor);
        this.tenantContexts = Objects.requireNonNull(tenantContexts);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.properties = Objects.requireNonNull(properties);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns once every status is durably recorded or dropped; the caller may acknowledge after that. */
    public void ingest(ParsedWebhook parsed) {
        telemetry.dropped(DropReason.UNSUPPORTED_EVENT, parsed.unsupportedCount());
        Map<String, Optional<TenantId>> tenants = new HashMap<>();
        Map<TenantId, List<StatusUpdate>> byTenant = new LinkedHashMap<>();
        for (StatusUpdate update : parsed.updates()) {
            Optional<TenantId> tenant = tenants.computeIfAbsent(update.phoneNumberId(), mappings::tenantFor);
            if (tenant.isEmpty()) {
                // Unknown, unverified or disabled mapping: the same 200, nothing stored (P3-HOOK-08, #210 gate).
                telemetry.dropped(DropReason.UNKNOWN_PHONE_NUMBER);
                continue;
            }
            byTenant.computeIfAbsent(tenant.get(), ignored -> new ArrayList<>()).add(update);
        }
        List<DueEvent> recorded = new ArrayList<>();
        for (Map.Entry<TenantId, List<StatusUpdate>> group : byTenant.entrySet()) {
            List<DueEvent> inserted = record(group.getKey(), group.getValue());
            recorded.addAll(inserted);
            telemetry.dropped(DropReason.DUPLICATE, group.getValue().size() - inserted.size());
        }
        // The sweeper applies anything beyond the inline limit, so a large batch does not hold the acknowledgement.
        processor.process(recorded.subList(0, Math.min(recorded.size(), properties.inlineLimit())));
    }

    /** One transaction per tenant: every status of that tenant is recorded, or none is and the route answers 503. */
    private List<DueEvent> record(TenantId tenantId, List<StatusUpdate> updates) {
        Instant now = clock.instant();
        return tenantContexts.callWithTenantId(tenantId, () -> transactions.execute(status -> {
            List<DueEvent> inserted = new ArrayList<>();
            for (StatusUpdate update : updates) {
                InboxEvent event = new InboxEvent(UUID.randomUUID(), tenantId, PROVIDER,
                        eventId(tenantId, update.providerMessageId(), update.status()), update.providerMessageId(),
                        update.status(), update.occurredAt(), update.failure(), 0, now);
                if (inbox.insertPending(event)) {
                    inserted.add(new DueEvent(tenantId, event.id()));
                }
            }
            return inserted;
        }));
    }

    /**
     * Meta statuses carry no event id. One message reaches each delivery state once, so (tenant, message, state) is
     * the identity: a byte-identical redelivery and a re-sent state with another timestamp both deduplicate. The
     * tenant keeps a report parked in one tenant from ever blocking the same id in another. The hash keeps the id
     * fixed length and keeps the provider message id out of the key.
     */
    static String eventId(TenantId tenantId, String providerMessageId, DeliveryStatus status) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((PROVIDER.name() + '|' + tenantId.value() + '|' + providerMessageId + '|'
                    + status.name())
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
