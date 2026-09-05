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
        if ((tenantId == null) != (actorId == null) || (tenantId == null) != (membershipId == null)) {
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
        }
    }
}
