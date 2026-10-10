// ABOUTME: MessagingProvider for the dev stack and tests: the outcome comes from the last two digits of the recipient.
// ABOUTME: 00 rejects, 01 fails transiently, 02 and 04 are unknown (04 with an id), 03 and the rest are accepted.
package io.github.stevdrey.dokene.integration.local;

import io.github.stevdrey.dokene.messaging.application.MessagingProvider;
import io.github.stevdrey.dokene.messaging.application.OutboundSendCommand;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatus;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalMessagingProvider implements MessagingProvider {
    static final String ID_PREFIX = "local-";
    private static final Logger log = LoggerFactory.getLogger(LocalMessagingProvider.class);
    private static final List<DeliveryStatus> DELIVERED = List.of(DeliveryStatus.DELIVERED);
    private static final List<DeliveryStatus> DELIVERED_THEN_READ = List.of(DeliveryStatus.DELIVERED,
            DeliveryStatus.READ);

    private final LocalDeliveryScheduler scheduler;

    public LocalMessagingProvider(LocalDeliveryScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "Delivery scheduler is required");
        log.warn("Local messaging provider active: sends are simulated and no message leaves this system");
    }

    @Override
    public ProviderSendResult send(OutboundSendCommand command) {
        Objects.requireNonNull(command, "Send command is required");
        String suffix = suffix(command.recipientPhone());
        ProviderSendResult result = switch (suffix) {
            case "00" -> new ProviderSendResult.RejectedPermanently(FailureCategory.INVALID_RECIPIENT);
            case "01" -> new ProviderSendResult.FailedTransiently(FailureCategory.RATE_LIMITED);
            case "02" -> new ProviderSendResult.OutcomeUnknown(Optional.empty());
            case "04" -> new ProviderSendResult.OutcomeUnknown(Optional.of(plan(command, DELIVERED)));
            case "03" -> new ProviderSendResult.Accepted(plan(command, DELIVERED_THEN_READ));
            default -> new ProviderSendResult.Accepted(plan(command, DELIVERED));
        };
        // The send key, not the provider id, correlates the line: provider ids never reach logs (invariant 24).
        log.info("Local provider simulated a send; tenantId={}, sendKey={}, outcome={}", command.tenantId(),
                command.sendKey(), result.getClass().getSimpleName());
        return result;
    }

    private String plan(OutboundSendCommand command, List<DeliveryStatus> reports) {
        String providerMessageId = ID_PREFIX + UUID.randomUUID();
        scheduler.plan(providerMessageId, command.tenantId(), reports);
        return providerMessageId;
    }

    /** The last two digits of the number's digits; a number with fewer than two digits is treated as accepted. */
    static String suffix(String recipientPhone) {
        String digits = recipientPhone.replaceAll("\\D", "");
        return digits.length() < 2 ? "" : digits.substring(digits.length() - 2);
    }
}
