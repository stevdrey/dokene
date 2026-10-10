// ABOUTME: Applies provider delivery reports (T13 to T16) under the provider context and the customer then message locks.
// ABOUTME: A late or duplicate report is recorded as a non applied event with a version bump and returns Ignored.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.FollowUpTouchRecorder;
import io.github.stevdrey.dokene.messaging.domain.DeliveryOutcome;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultDeliveryStatusSink implements DeliveryStatusSink {
    private static final Logger log = LoggerFactory.getLogger(DefaultDeliveryStatusSink.class);
    private static final Set<MessageStatus> SENT_STATES = Set.of(MessageStatus.SENT, MessageStatus.DELIVERED,
            MessageStatus.READ);

    private final OutboundMessageRepository messages;
    private final CustomerRepository customers;
    private final FollowUpTouchRecorder touches;
    private final MessageAuditPort audit;
    private final ProviderContextProvider providerContexts;
    private final TenantContextProvider tenantContexts;
    private final Clock clock;

    public DefaultDeliveryStatusSink(OutboundMessageRepository messages, CustomerRepository customers,
            FollowUpTouchRecorder touches, MessageAuditPort audit, ProviderContextProvider providerContexts,
            TenantContextProvider tenantContexts, Clock clock) {
        this.messages = Objects.requireNonNull(messages);
        this.customers = Objects.requireNonNull(customers);
        this.touches = Objects.requireNonNull(touches);
        this.audit = Objects.requireNonNull(audit);
        this.providerContexts = Objects.requireNonNull(providerContexts);
        this.tenantContexts = Objects.requireNonNull(tenantContexts);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public DeliveryStatusResult apply(DeliveryStatusReport report) {
        Objects.requireNonNull(report, "Report is required");
        ProviderContext provider = providerContexts.current()
                .orElseThrow(() -> new MessagingRefusedException(MessagingErrorCode.FORBIDDEN));
        if (tenantContexts.current().isPresent()) {
            throw new MessagingRefusedException(MessagingErrorCode.FORBIDDEN);
        }
        // The RLS tenant comes from the trusted provider context, never from the report.
        return tenantContexts.callWithTenantId(provider.tenantId(), () -> applyUnderTenant(provider, report));
    }

    private DeliveryStatusResult applyUnderTenant(ProviderContext provider, DeliveryStatusReport report) {
        TenantId tenantId = provider.tenantId();
        UUID tenant = tenantId.value();
        var ref = messages.findIdsByProviderMessageId(tenant, report.providerMessageId()).orElse(null);
        if (ref == null) {
            return new DeliveryStatusResult.NotFound();
        }
        customers.findByIdForUpdate(tenantId, new CustomerId(ref.customerId()))
                .orElseThrow(() -> new MessagingRefusedException(MessagingErrorCode.NOT_FOUND));
        OutboundMessage message = messages.findByIdForUpdate(tenant, ref.messageId()).orElse(null);
        if (message == null || !report.providerMessageId().equals(message.providerMessageId())) {
            return new DeliveryStatusResult.NotFound();
        }
        DeliveryOutcome outcome = message.applyDeliveryReport(report, clock.instant());
        return switch (outcome) {
            case DeliveryOutcome.Applied applied -> {
                OutboundMessage next = messages.update(message, applied.transition().next());
                messages.appendEvent(applied.transition().event());
                if (message.status() == MessageStatus.SENDING && SENT_STATES.contains(next.status())) {
                    touches.recordOutboundMessage(new CustomerId(next.customerId()), next.sentAt());
                }
                audit.deliveryUpdated(provider, applied.transition().event());
                log.info("Delivery report applied; messageId={}, tenantId={}, statusFrom={}, statusTo={}",
                        next.id(), tenant, message.status(), next.status());
                yield new DeliveryStatusResult.Applied(message.status(), next.status());
            }
            case DeliveryOutcome.Ignored ignored -> {
                messages.update(message, ignored.next());
                messages.appendEvent(ignored.event());
                log.info("Delivery report ignored; messageId={}, tenantId={}, status={}", message.id(), tenant,
                        message.status());
                yield new DeliveryStatusResult.Ignored();
            }
        };
    }
}
