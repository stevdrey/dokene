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

    /** A tenant setting changed (issue 452); the row names the tenant only, never the old or new value. */
    record TenantSettingChange() implements AuditMetadata {
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

    /**
     * Message lifecycle transition (ADR 0023 §4.5): closed status and category vocabularies only; never a body,
     * phone number, note or provider message id. {@code from} is null only for the submit event.
     */
    record MessageTransition(AuditMessageStatus from, AuditMessageStatus to, AuditFailureCategory failureCategory,
            Integer attemptNumber) implements AuditMetadata {
        public MessageTransition {
            Objects.requireNonNull(to, "Target status is required");
            if (attemptNumber != null && attemptNumber < 1) {
                throw new IllegalArgumentException("Attempt number starts at 1");
            }
        }
    }

    /** Template mapping, integration and kill switch changes record only the resulting enabled flag. */
    record IntegrationToggle(boolean enabled) implements AuditMetadata {
    }

    record AuthorizationDenied(TenantPermission permission, AuditDenialReason reason) implements AuditMetadata {
        public AuthorizationDenied {
            Objects.requireNonNull(reason, "Denial reason is required");
        }
    }

    /**
     * A count of denials that got no row of their own (spec 0020, ADR 0032). {@code reason} is null only for the
     * count taken while the tracker was full, which belongs to no key, and then {@code permission} is null too.
     */
    record AuthorizationDenialsSuppressed(TenantPermission permission, AuditDenialReason reason, int suppressedCount)
            implements AuditMetadata {
        public AuthorizationDenialsSuppressed {
            if (reason == null && permission != null) {
                throw new IllegalArgumentException("A count without a reason carries no permission");
            }
            if (suppressedCount < 1) {
                throw new IllegalArgumentException("Suppressed count starts at 1");
            }
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

    /** An invitation accepted by its invitee, or a revoked membership reactivated as a new invitation. */
    record MembershipStatusChanged(TenantRole role) implements AuditMetadata {
        public MembershipStatusChanged {
            Objects.requireNonNull(role, "Role is required");
            if (role == TenantRole.OWNER) {
                throw new IllegalArgumentException("Ownership is never granted through an invitation");
            }
        }
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
