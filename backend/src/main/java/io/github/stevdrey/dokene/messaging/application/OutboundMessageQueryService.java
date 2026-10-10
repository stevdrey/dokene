// ABOUTME: Read only queries behind GET /api/messages/** and the response of every message command (spec 0002).
// ABOUTME: MESSAGE_READ is required on the GET paths; the post command read skips it because the command authorized.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessageEvent;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboundMessageQueryService {
    private final OutboundMessageQueryRepository queries;
    private final OutboundMessageRepository messages;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;

    public OutboundMessageQueryService(OutboundMessageQueryRepository queries, OutboundMessageRepository messages,
            TenantAuthorizationService authorization, TenantContextProvider contexts) {
        this.queries = Objects.requireNonNull(queries, "Query repository is required");
        this.messages = Objects.requireNonNull(messages, "Message repository is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization service is required");
        this.contexts = Objects.requireNonNull(contexts, "Context provider is required");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MessageDetail detail(UUID messageId) {
        require(TenantPermission.MESSAGE_READ);
        return readDetail(messageId);
    }

    /**
     * The representation a command returns after it committed. The command already authorized the caller, so no
     * permission is re checked; one snapshot (repeatable read) keeps the message and its children consistent.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MessageDetail detailAfterCommand(UUID messageId) {
        return readDetail(messageId);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MessagePage list(MessageListQuery query) {
        Objects.requireNonNull(query, "Query is required");
        require(TenantPermission.MESSAGE_READ);
        return queries.findPage(tenantId(), query);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<MessageEvent> events(UUID messageId) {
        Objects.requireNonNull(messageId, "Message id is required");
        require(TenantPermission.MESSAGE_READ);
        UUID tenantId = tenantId();
        if (messages.findById(tenantId, messageId).isEmpty()) {
            throw new MessagingRefusedException(MessagingErrorCode.NOT_FOUND);
        }
        return messages.findEvents(tenantId, messageId);
    }

    private MessageDetail readDetail(UUID messageId) {
        Objects.requireNonNull(messageId, "Message id is required");
        return queries.findDetail(tenantId(), messageId)
                .orElseThrow(() -> new MessagingRefusedException(MessagingErrorCode.NOT_FOUND));
    }

    private void require(TenantPermission permission) {
        try {
            authorization.requirePermission(permission);
        } catch (TenantAccessDeniedException denied) {
            throw new MessagingRefusedException(MessagingErrorCode.FORBIDDEN);
        }
    }

    private UUID tenantId() {
        try {
            return contexts.requireCurrent().tenantId().value();
        } catch (TenantContextUnavailableException unavailable) {
            throw new MessagingRefusedException(MessagingErrorCode.FORBIDDEN);
        }
    }
}
