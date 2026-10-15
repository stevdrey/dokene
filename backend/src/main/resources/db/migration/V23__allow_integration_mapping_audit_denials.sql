-- ABOUTME: Allows the MAPPING_REJECTED and RATE_LIMITED denial reasons on AUTHORIZATION_DENIED (issue 208): rejected
-- ABOUTME: and throttled WhatsApp mappings are audited without any identifier. Other branches are copied from V19.

ALTER TABLE dokene.audit_events DROP CONSTRAINT ck_audit_shape;
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_shape CHECK (
    (event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
        AND target_type IS NULL AND target_id IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND denial_reason IS NOT NULL AND denial_reason IN (
            'NO_TENANT_CONTEXT', 'INACTIVE_MEMBERSHIP', 'MISSING_ROLE', 'MISSING_PERMISSION',
            'MISSING_RESOURCE_TENANT', 'CROSS_TENANT_RESOURCE', 'INSUFFICIENT_PERMISSION', 'MAPPING_REJECTED',
            'RATE_LIMITED', 'UNSPECIFIED')
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
    OR (event_type IN ('CUSTOMER_CREATED', 'CUSTOMER_UPDATED', 'CUSTOMER_ARCHIVED',
            'CUSTOMER_CONSENT_CHANGED', 'CUSTOMER_DO_NOT_CONTACT_CHANGED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type IN ('PURCHASE_RECORDED', 'PURCHASE_CORRECTED', 'PURCHASE_VOIDED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'PURCHASE' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type = 'TENANT_FOLLOW_UP_POLICY_CHANGED'
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'TENANT' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type IN ('CUSTOMER_FOLLOW_UP_POLICY_CHANGED', 'FOLLOW_UP_SNOOZED',
            'MANUAL_FOLLOW_UP_RECORDED', 'FOLLOW_UP_DISMISSED')
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
                'CANCELLED', 'NOT_AVAILABLE', 'REFUSED', 'CONTEXT_TOO_LARGE', 'CONTEXT_UNSUPPORTED'))))
    OR (event_type IN ('MESSAGE_SUBMITTED', 'MESSAGE_APPROVED', 'MESSAGE_REJECTED', 'MESSAGE_CANCELLED',
            'MESSAGE_SEND_REQUESTED', 'MESSAGE_SENT', 'MESSAGE_SEND_FAILED', 'MESSAGE_SEND_OUTCOME_UNKNOWN',
            'MESSAGE_DELIVERY_UPDATED')
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
);
