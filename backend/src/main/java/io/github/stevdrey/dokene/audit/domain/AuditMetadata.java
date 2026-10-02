package io.github.stevdrey.dokene.audit.domain;

import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.Objects;

/** Closed, scalar-only metadata. Never accepts free text or arbitrary payloads. */
public sealed interface AuditMetadata {

    record CustomerMutation() implements AuditMetadata {
    }

    record PurchaseMutation() implements AuditMetadata {
    }

    record FollowUpMutation() implements AuditMetadata {
    }

    /** Privacy-safe AI lifecycle outcome: enums only, never prompts, generated text, notes or provider messages. */
    record AiInvocation(AiAuditOperation operation, AiAuditOutcome outcome, AiAuditDetail detail)
            implements AuditMetadata {
        public AiInvocation {
            Objects.requireNonNull(operation, "AI operation is required");
            Objects.requireNonNull(outcome, "AI outcome is required");
            Objects.requireNonNull(detail, "AI outcome detail is required");
            boolean consistent = switch (outcome) {
                case GENERATED, MODEL_REFUSED -> detail.isNone();
                case GATE_REJECTED -> detail.isGateReason();
                case FAILED -> detail.isFailure();
            };
            if (!consistent) {
                throw new IllegalArgumentException("AI outcome detail is inconsistent with the outcome");
            }
        }
    }

    record AuthorizationDenied(TenantPermission permission, AuditDenialReason reason) implements AuditMetadata {
        public AuthorizationDenied {
            Objects.requireNonNull(reason, "Denial reason is required");
        }
    }

    record MembershipCreated(TenantRole role) implements AuditMetadata {
        public MembershipCreated {
            Objects.requireNonNull(role, "Role is required");
            if (role == TenantRole.OWNER) {
                throw new IllegalArgumentException("Ownership cannot be assigned via invitation");
            }
        }
    }

    record MembershipRevoked() implements AuditMetadata {
    }

    record MembershipRoleChanged(TenantRole previousRole, TenantRole newRole) implements AuditMetadata {
        public MembershipRoleChanged {
            Objects.requireNonNull(previousRole, "Previous role is required");
            Objects.requireNonNull(newRole, "New role is required");
            if (previousRole == newRole || previousRole == TenantRole.OWNER || newRole == TenantRole.OWNER) {
                throw new IllegalArgumentException("Unsupported role transition");
            }
        }
    }
}
