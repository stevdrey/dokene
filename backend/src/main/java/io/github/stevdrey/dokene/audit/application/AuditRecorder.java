package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AiAuditDetail;
import io.github.stevdrey.dokene.audit.domain.AiAuditOperation;
import io.github.stevdrey.dokene.audit.domain.AiAuditOutcome;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.AuditTarget;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.UUID;

/** Attribution and event IDs/timestamps are always supplied by the server implementation. */
public interface AuditRecorder {
    void authorizationDenied(TenantPermission permission, AuditDenialReason reason);

    /** Requires an existing business transaction; failure must roll back the state transition. */
    void membershipRoleChanged(TenantMembershipId target, TenantRole previousRole, TenantRole newRole);

    /** Requires an existing business transaction; failure must roll back the state transition. */
    default void membershipCreated(TenantMembershipId target, TenantRole role) {
    }

    /** Requires an existing business transaction; failure must roll back the state transition. */
    default void membershipRevoked(TenantMembershipId target) {
    }

    /** Requires an existing business transaction; failure must roll back the state transition. */
    void customerMutated(UUID target, AuditEventType eventType);

    /** Requires an existing business transaction; failure must roll back the state transition. */
    void purchaseMutated(UUID target, AuditEventType eventType);

    /** Requires an existing business transaction; failure must roll back the state transition. */
    default void followUpMutated(AuditTarget.Type targetType, UUID target, AuditEventType eventType) {
        throw new UnsupportedOperationException("Follow-up audit is not configured");
    }

    /**
     * Independently durable (own transaction) so failed and rejected invocations are recorded even though no
     * business transaction exists. Metadata is a closed vocabulary; never pass prompts, text or provider messages.
     */
    default void aiInvocationOutcome(UUID customerId, AiAuditOperation operation, AiAuditOutcome outcome,
            AiAuditDetail detail) {
        throw new UnsupportedOperationException("AI invocation audit is not configured");
    }

    /**
     * Requires an existing business transaction; failure must roll back the message transition. Attributed to the
     * current member context. Metadata is a closed vocabulary; never pass bodies, phones, notes or provider ids.
     */
    default void messageTransition(UUID messageId, AuditEventType eventType,
            AuditMetadata.MessageTransition metadata) {
        throw new UnsupportedOperationException("Message audit is not configured");
    }

    /**
     * Requires an existing business transaction. Written for the provider webhook path with tenant attribution and
     * no actor or membership (ADR 0023 §4.5); the caller must have established that tenant as the RLS context.
     */
    default void messageDeliveryUpdated(TenantId tenantId, UUID messageId,
            AuditMetadata.MessageTransition metadata) {
        throw new UnsupportedOperationException("Message audit is not configured");
    }
}
