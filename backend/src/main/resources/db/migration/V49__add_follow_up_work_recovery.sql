-- ABOUTME: Issue 461 (Phase 4 A3, ADR 0038): bounded retries and explicit recovery of follow-up work. Adds the REJECTED
-- ABOUTME: failure, the attempt budget base and recovery columns, and the two audited recovery decisions.

-- Expand only: a backend of the previous release never writes the new columns, whose defaults keep its rows valid, and
-- never records REJECTED. attempt_base is how many attempts the item had used when recovery last requeued it, so the
-- attempt limit counts the claims since then while the attempt number, part of the fencing identity, keeps growing.
ALTER TABLE dokene.follow_up_work_items
    ADD COLUMN attempt_base INTEGER NOT NULL DEFAULT 0,
    -- Recovery decisions recorded so far; a decision names it as the version it expects (If-Match).
    ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_recovery_action VARCHAR(16),
    ADD COLUMN last_recovered_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_follow_up_work_items_attempt_base CHECK (attempt_base >= 0 AND attempt_base <= attempts),
    ADD CONSTRAINT ck_follow_up_work_items_recovery CHECK (
        recovery_count >= 0
        AND (recovery_count = 0) = (last_recovery_action IS NULL)
        AND (last_recovery_action IS NULL) = (last_recovered_at IS NULL)
        AND (last_recovery_action IS NULL OR last_recovery_action IN ('REQUEUE', 'RESOLVE'))
        -- Only a failure known to have had no external effect is ever requeued (ADR 0038). The requeue update itself
        -- enforces that, because a requeued item's later attempts may still end with an unknown outcome.
        AND (last_recovery_action IS NULL OR (last_failure IS NOT NULL AND collapsed_into IS NULL))),
    DROP CONSTRAINT ck_follow_up_work_items_failure,
    ADD CONSTRAINT ck_follow_up_work_items_failure CHECK (
        (state NOT IN ('RETRY_DUE', 'EXHAUSTED') OR last_failure IS NOT NULL)
        AND (last_failure IS NULL OR last_failure IN (
            'PROCESSING_ERROR', 'DEPENDENCY_UNAVAILABLE', 'REJECTED', 'LEASE_EXPIRED', 'OUTCOME_UNKNOWN')));

-- The recovery list reads a tenant's exhausted items awaiting a decision, oldest finish first.
CREATE INDEX ix_follow_up_work_items_recoverable ON dokene.follow_up_work_items (tenant_id, finished_at)
    WHERE state = 'EXHAUSTED' AND collapsed_into IS NULL;

-- The other branches are copied unchanged from V44; only the customer follow-up branch gains the two recovery
-- decisions, which carry no metadata beyond their customer target.
ALTER TABLE dokene.audit_events
    DROP CONSTRAINT ck_audit_shape,
    ADD CONSTRAINT ck_audit_shape CHECK (
    (event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
        AND target_type IS NULL AND target_id IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND denial_reason IS NOT NULL AND denial_reason IN (
            'NO_TENANT_CONTEXT', 'INACTIVE_MEMBERSHIP', 'MISSING_ROLE', 'MISSING_PERMISSION',
            'MISSING_RESOURCE_TENANT', 'CROSS_TENANT_RESOURCE', 'INSUFFICIENT_PERMISSION', 'MAPPING_REJECTED',
            'RATE_LIMITED', 'PROVIDER_CALL_LIMITED', 'UNSPECIFIED')
        AND ((tenant_id IS NULL AND denial_reason = 'NO_TENANT_CONTEXT')
            OR (tenant_id IS NOT NULL AND denial_reason <> 'NO_TENANT_CONTEXT')))
    OR (event_type = 'MEMBERSHIP_ROLE_CHANGED' AND outcome = 'SUCCESS'
        AND tenant_id IS NOT NULL AND target_type = 'MEMBERSHIP' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL
        AND previous_role IN ('ADMIN', 'OPERATOR', 'VIEWER') AND new_role IN ('ADMIN', 'OPERATOR', 'VIEWER')
        AND previous_role <> new_role)
    OR (event_type = 'MEMBERSHIP_CREATED' AND outcome = 'SUCCESS'
        AND tenant_id IS NOT NULL AND target_type = 'MEMBERSHIP' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL
        AND new_role IN ('ADMIN', 'OPERATOR', 'VIEWER'))
    OR (event_type = 'MEMBERSHIP_REVOKED' AND outcome = 'SUCCESS'
        AND tenant_id IS NOT NULL AND target_type = 'MEMBERSHIP' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type IN ('MEMBERSHIP_INVITATION_ACCEPTED', 'MEMBERSHIP_REACTIVATED') AND outcome = 'SUCCESS'
        AND tenant_id IS NOT NULL AND actor_id IS NOT NULL AND membership_id IS NOT NULL
        AND target_type = 'MEMBERSHIP' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL
        AND new_role IN ('ADMIN', 'OPERATOR', 'VIEWER'))
    OR (event_type IN ('CUSTOMER_CREATED', 'CUSTOMER_UPDATED', 'CUSTOMER_ARCHIVED',
            'CUSTOMER_CONSENT_CHANGED', 'CUSTOMER_DO_NOT_CONTACT_CHANGED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type IN ('PURCHASE_RECORDED', 'PURCHASE_CORRECTED', 'PURCHASE_VOIDED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'PURCHASE' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type IN ('TENANT_FOLLOW_UP_POLICY_CHANGED', 'TENANT_OPERATING_CURRENCY_CHANGED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'TENANT' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type IN ('CUSTOMER_FOLLOW_UP_POLICY_CHANGED', 'FOLLOW_UP_SNOOZED',
            'MANUAL_FOLLOW_UP_RECORDED', 'FOLLOW_UP_DISMISSED',
            'FOLLOW_UP_WORK_REQUEUED', 'FOLLOW_UP_WORK_RESOLVED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type = 'AI_INVOCATION_OUTCOME'
        AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND ai_operation IN ('NEXT_BEST_ACTION', 'MESSAGE_DRAFT')
        AND ((ai_outcome IN ('GENERATED', 'MODEL_REFUSED') AND outcome = 'SUCCESS' AND ai_detail = 'NONE')
            OR (ai_outcome = 'GATE_REJECTED' AND outcome = 'DENIED' AND ai_detail IN (
                'NO_TENANT_CONTEXT', 'UNAUTHORIZED', 'CUSTOMER_NOT_FOUND', 'CUSTOMER_ARCHIVED', 'DO_NOT_CONTACT',
                'NO_CONTACT_CONSENT', 'FOLLOW_UP_INELIGIBLE', 'STALE_STATE', 'DISALLOWED_ACTION',
                'DISALLOWED_TEMPLATE_INTENT', 'INVALID_RECOMMENDATION'))
            OR (ai_outcome = 'FAILED' AND outcome = 'FAILURE' AND ai_detail IN (
                'TIMEOUT', 'THROTTLED', 'UNAVAILABLE', 'INVALID_STRUCTURED_RESPONSE', 'REJECTED_REQUEST',
                'CANCELLED', 'NOT_AVAILABLE', 'REFUSED', 'CONTEXT_TOO_LARGE', 'CONTEXT_UNSUPPORTED',
                'TOKEN_BUDGET_EXHAUSTED'))))
    OR (event_type IN ('MESSAGE_SUBMITTED', 'MESSAGE_APPROVED', 'MESSAGE_REJECTED', 'MESSAGE_CANCELLED',
            'MESSAGE_SEND_REQUESTED', 'MESSAGE_SENT', 'MESSAGE_SEND_FAILED', 'MESSAGE_SEND_OUTCOME_UNKNOWN',
            'MESSAGE_SEND_RESOLVED', 'MESSAGE_DELIVERY_UPDATED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'MESSAGE' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND enabled IS NULL
        AND status_to IS NOT NULL AND status_to IN (
            'PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ',
            'REJECTED', 'CANCELLED', 'FAILED')
        AND ((event_type = 'MESSAGE_SUBMITTED' AND status_from IS NULL)
            OR (event_type <> 'MESSAGE_SUBMITTED' AND status_from IS NOT NULL AND status_from IN (
                'PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ',
                'REJECTED', 'CANCELLED', 'FAILED')))
        AND (failure_category IS NULL OR failure_category IN (
            'INVALID_RECIPIENT', 'TEMPLATE_REJECTED', 'RATE_LIMITED', 'PROVIDER_UNAVAILABLE',
            'AUTHENTICATION', 'POLICY_VIOLATION', 'UNKNOWN'))
        AND (attempt_number IS NULL OR attempt_number >= 1))
    OR (event_type = 'TEMPLATE_MAPPING_UPDATED'
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'TEMPLATE_MAPPING' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND enabled IS NOT NULL AND status_from IS NULL AND status_to IS NULL
        AND failure_category IS NULL AND attempt_number IS NULL)
    OR (event_type IN ('INTEGRATION_UPDATED', 'OUTBOUND_KILL_SWITCH_CHANGED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'INTEGRATION' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND enabled IS NOT NULL AND status_from IS NULL AND status_to IS NULL
        AND failure_category IS NULL AND attempt_number IS NULL)
    -- A count of denials that got no row of their own. Three shapes: attributed (a tenant and a tenant reason),
    -- unattributed (no tenant, NO_TENANT_CONTEXT) and tracker full (no tenant, no reason, no permission). Every
    -- nullable column is tested with IS NULL or IS NOT NULL first, because a CHECK passes when its expression is
    -- UNKNOWN.
    OR (event_type = 'AUTHORIZATION_DENIALS_SUPPRESSED' AND outcome = 'DENIED'
        AND target_type IS NULL AND target_id IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND suppressed_count IS NOT NULL
        AND ((tenant_id IS NOT NULL AND denial_reason IS NOT NULL AND denial_reason IN (
                'INACTIVE_MEMBERSHIP', 'MISSING_ROLE', 'MISSING_PERMISSION',
                'MISSING_RESOURCE_TENANT', 'CROSS_TENANT_RESOURCE', 'INSUFFICIENT_PERMISSION', 'MAPPING_REJECTED',
                'RATE_LIMITED', 'PROVIDER_CALL_LIMITED', 'UNSPECIFIED'))
            OR (tenant_id IS NULL AND denial_reason IS NOT NULL AND denial_reason = 'NO_TENANT_CONTEXT')
            OR (tenant_id IS NULL AND denial_reason IS NULL AND permission IS NULL)))
);
