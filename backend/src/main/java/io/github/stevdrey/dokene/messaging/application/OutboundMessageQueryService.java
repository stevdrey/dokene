// ABOUTME: Read side of /api/messages: detail, keyset-paged list and append-only event history (ADR 0023 §3.1).
// ABOUTME: Tenant-scoped through the signed context; anything outside it reads as NOT_FOUND, never as forbidden.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository.ListedMessage;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingLocales;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboundMessageQueryService {
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final OutboundMessageRepository messages;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final MessageTemplatePreviews previews;
    private final EnglishOutboundProperties english;

    public OutboundMessageQueryService(OutboundMessageRepository messages, TenantAuthorizationService authorization,
            TenantContextProvider contexts, MessageTemplatePreviews previews, EnglishOutboundProperties english) {
        this.messages = Objects.requireNonNull(messages);
        this.authorization = Objects.requireNonNull(authorization);
        this.contexts = Objects.requireNonNull(contexts);
        this.previews = Objects.requireNonNull(previews);
        this.english = Objects.requireNonNull(english);
    }

    /**
     * The locales a new message may be submitted in on this deployment (issue 268), the same list submit enforces.
     * Deployment configuration, not tenant data; the caller's permission is checked by the route.
     */
    public List<String> acceptedLocales() {
        return MessagingLocales.accepted(english.enabled());
    }

    public record MessagePage(List<ListedMessage> items, String nextCursor) {
        public MessagePage {
            items = List.copyOf(Objects.requireNonNull(items, "Items are required"));
        }
    }

    /**
     * The message, its approval and its attempts are separate statements, so the read runs under one REPEATABLE READ
     * snapshot: a transition committing between them cannot pair an older message version with a newer child row.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MessageDetail detail(UUID messageId) {
        UUID tenantId = authorizedTenant();
        ListedMessage listed = messages.findListedById(tenantId, Objects.requireNonNull(messageId))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        Optional<MessageApproval> approval = messages.findApprovalByMessage(tenantId, messageId);
        return new MessageDetail(listed.message(), listed.contactRemoved(),
                approval, messages.findAttempts(tenantId, messageId),
                previews.of(listed.message(), approval));
    }

    /** An unknown or foreign {@code customerId} simply matches nothing. */
    @Transactional(readOnly = true)
    public MessagePage page(Set<MessageStatus> statuses, Optional<UUID> customerId, Optional<String> cursor,
            int limit) {
        UUID tenantId = authorizedTenant();
        if (limit < 1 || limit > MAX_LIMIT) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        Optional<MessageCursor> after;
        try {
            after = cursor.map(MessageCursor::decode);
        } catch (IllegalArgumentException malformed) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        List<ListedMessage> rows = messages.findPage(tenantId, Set.copyOf(statuses), customerId, after, limit + 1);
        if (rows.size() <= limit) {
            return new MessagePage(rows, null);
        }
        List<ListedMessage> items = rows.subList(0, limit);
        OutboundMessage last = items.getLast().message();
        return new MessagePage(items, new MessageCursor(last.createdAt(), last.id()).encode());
    }

    /** Ordered by sequence number; the message must be visible in the tenant. */
    @Transactional(readOnly = true)
    public List<MessageEvent> events(UUID messageId) {
        UUID tenantId = authorizedTenant();
        messages.findById(tenantId, Objects.requireNonNull(messageId))
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        return messages.findEvents(tenantId, messageId);
    }

    private UUID authorizedTenant() {
        try {
            authorization.requirePermission(TenantPermission.MESSAGE_READ);
            return contexts.requireCurrent().tenantId().value();
        } catch (TenantAccessDeniedException | TenantContextUnavailableException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
    }

    private static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
