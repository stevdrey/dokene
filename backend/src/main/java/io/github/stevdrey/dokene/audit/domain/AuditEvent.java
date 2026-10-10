package io.github.stevdrey.dokene.audit.domain;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/** Historical references deliberately have no mutable entity or payload association. */
public record AuditEvent(
        UUID id, Instant timestamp, TenantId tenantId, IdentityId actorId,
        TenantMembershipId membershipId, AuditEventType type, AuditTarget target,
        AuditOutcome outcome, UUID correlationId, AuditMetadata metadata
) {
    public AuditEvent {
        Objects.requireNonNull(id, "Event ID is required");
        timestamp = Objects.requireNonNull(timestamp, "Timestamp is required").truncatedTo(ChronoUnit.MICROS);
        Objects.requireNonNull(type, "Event type is required");
        Objects.requireNonNull(outcome, "Outcome is required");
        Objects.requireNonNull(correlationId, "Correlation ID is required");
        Objects.requireNonNull(metadata, "Metadata is required");
        // Provider driven delivery updates are attributed to the tenant alone (ADR 0023 §4.5).
        boolean providerAttributed = type == AuditEventType.MESSAGE_DELIVERY_UPDATED
                && tenantId != null && actorId == null && membershipId == null;
        if (!providerAttributed
                && ((tenantId == null) != (actorId == null) || (tenantId == null) != (membershipId == null))) {
            throw new IllegalArgumentException("Attribution must be complete or absent");
        }
        switch (type) {
            case AUTHORIZATION_DENIED -> {
                if (outcome != AuditOutcome.DENIED || target != null
                        || !(metadata instanceof AuditMetadata.AuthorizationDenied denial)
                        || ((tenantId == null) != (denial.reason() == AuditDenialReason.NO_TENANT_CONTEXT))) {
                    throw new IllegalArgumentException("Invalid authorization denial event");
                }
            }
            case MEMBERSHIP_ROLE_CHANGED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.MEMBERSHIP
                        || !(metadata instanceof AuditMetadata.MembershipRoleChanged)) {
                    throw new IllegalArgumentException("Invalid membership role event");
                }
            }
            case MEMBERSHIP_CREATED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.MEMBERSHIP
                        || !(metadata instanceof AuditMetadata.MembershipCreated)) {
                    throw new IllegalArgumentException("Invalid membership created event");
                }
            }
            case MEMBERSHIP_REVOKED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.MEMBERSHIP
                        || !(metadata instanceof AuditMetadata.MembershipRevoked)) {
                    throw new IllegalArgumentException("Invalid membership revoked event");
                }
            }
            case CUSTOMER_CREATED, CUSTOMER_UPDATED, CUSTOMER_ARCHIVED,
                    CUSTOMER_CONSENT_CHANGED, CUSTOMER_DO_NOT_CONTACT_CHANGED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.CUSTOMER
                        || !(metadata instanceof AuditMetadata.CustomerMutation)) {
                    throw new IllegalArgumentException("Invalid customer mutation event");
                }
            }
            case PURCHASE_RECORDED, PURCHASE_CORRECTED, PURCHASE_VOIDED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.PURCHASE
                        || !(metadata instanceof AuditMetadata.PurchaseMutation)) {
                    throw new IllegalArgumentException("Invalid purchase mutation event");
                }
            }
            case TENANT_FOLLOW_UP_POLICY_CHANGED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.TENANT
                        || !(metadata instanceof AuditMetadata.FollowUpMutation)) {
                    throw new IllegalArgumentException("Invalid tenant follow-up mutation event");
                }
            }
            case CUSTOMER_FOLLOW_UP_POLICY_CHANGED, FOLLOW_UP_SNOOZED, MANUAL_FOLLOW_UP_RECORDED,
                    FOLLOW_UP_DISMISSED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.CUSTOMER
                        || !(metadata instanceof AuditMetadata.FollowUpMutation)) {
                    throw new IllegalArgumentException("Invalid customer follow-up mutation event");
                }
            }
            case AI_INVOCATION_OUTCOME -> {
                if (tenantId == null || target == null || target.type() != AuditTarget.Type.CUSTOMER
                        || !(metadata instanceof AuditMetadata.AiInvocation ai)
                        || outcome != expectedOutcome(ai.outcome())) {
                    throw new IllegalArgumentException("Invalid AI invocation outcome event");
                }
            }
            case MESSAGE_SUBMITTED, MESSAGE_APPROVED, MESSAGE_REJECTED, MESSAGE_CANCELLED, MESSAGE_SEND_REQUESTED,
                    MESSAGE_SENT, MESSAGE_SEND_FAILED, MESSAGE_SEND_OUTCOME_UNKNOWN, MESSAGE_DELIVERY_UPDATED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.MESSAGE
                        || !(metadata instanceof AuditMetadata.MessageTransition transition)
                        || (transition.from() == null) != (type == AuditEventType.MESSAGE_SUBMITTED)) {
                    throw new IllegalArgumentException("Invalid message transition event");
                }
            }
            case TEMPLATE_MAPPING_UPDATED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.TEMPLATE_MAPPING
                        || !(metadata instanceof AuditMetadata.IntegrationToggle)) {
                    throw new IllegalArgumentException("Invalid template mapping event");
                }
            }
            case INTEGRATION_UPDATED, OUTBOUND_KILL_SWITCH_CHANGED -> {
                if (outcome != AuditOutcome.SUCCESS || tenantId == null || target == null
                        || target.type() != AuditTarget.Type.INTEGRATION
                        || !(metadata instanceof AuditMetadata.IntegrationToggle)) {
                    throw new IllegalArgumentException("Invalid integration event");
                }
            }
        }
    }

    private static AuditOutcome expectedOutcome(AiAuditOutcome ai) {
        return switch (ai) {
            case GENERATED, MODEL_REFUSED -> AuditOutcome.SUCCESS;
            case GATE_REJECTED -> AuditOutcome.DENIED;
            case FAILED -> AuditOutcome.FAILURE;
        };
    }
}
