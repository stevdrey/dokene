// ABOUTME: What every message command shares: header grammar, the cached permission check, the current context and the
// ABOUTME: detail read inside the command's transaction. Used by the T1 to T5 commands and by manual dispatch.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class MessageCommandSupport {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Pattern IF_MATCH = Pattern.compile("^[0-9]{1,19}$");

    private final OutboundMessageRepository messages;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;

    MessageCommandSupport(OutboundMessageRepository messages, TenantAuthorizationService authorization,
            TenantContextProvider contexts) {
        this.messages = Objects.requireNonNull(messages);
        this.authorization = Objects.requireNonNull(authorization);
        this.contexts = Objects.requireNonNull(contexts);
    }

    /** A strong numeric version, already unquoted by the controller. */
    long ifMatch(String value) {
        if (value == null || !IF_MATCH.matcher(value.strip()).matches()) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        try {
            return Long.parseLong(value.strip());
        } catch (NumberFormatException exception) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
    }

    String idempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw refuse(MessagingErrorCode.INVALID_INPUT);
        }
        return value;
    }

    /** The request-level check, audited by the authorization service; commands re-check under their lock too. */
    void require(TenantPermission permission) {
        try {
            authorization.requirePermission(permission);
        } catch (TenantAccessDeniedException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
    }

    boolean allows(TenantPermission permission) {
        return authorization.evaluate(permission).isAllowed();
    }

    TenantContext currentContext() {
        try {
            return contexts.requireCurrent();
        } catch (TenantContextUnavailableException exception) {
            throw refuse(MessagingErrorCode.FORBIDDEN);
        }
    }

    /**
     * The message detail, read inside the command's transaction while it holds the message row lock, so it cannot mix
     * versions and needs no second authorization after commit.
     */
    MessageDetail detail(UUID tenantId, UUID messageId) {
        var listed = messages.findListedById(tenantId, messageId)
                .orElseThrow(() -> refuse(MessagingErrorCode.NOT_FOUND));
        return new MessageDetail(listed.message(), listed.contactRemoved(),
                messages.findApprovalByMessage(tenantId, messageId), messages.findAttempts(tenantId, messageId));
    }

    CommandResult result(UUID tenantId, UUID messageId, Optional<Object> record, boolean created) {
        return new CommandResult(detail(tenantId, messageId), record, created);
    }

    static MessagingRefusedException refuse(MessagingErrorCode code) {
        return new MessagingRefusedException(code);
    }
}
