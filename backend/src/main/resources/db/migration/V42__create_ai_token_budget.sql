-- ABOUTME: Issue 346 (audit L10, ADR 0035): a per-tenant monthly AI token counter shared by every backend instance,
-- ABOUTME: and the FAILED audit detail TOKEN_BUDGET_EXHAUSTED for an invocation refused before any provider call.

-- Expand only: a backend of the previous release never reads or writes the new table and never writes the new
-- detail, and every row it writes still passes the widened ck_audit_shape.
CREATE TABLE dokene.ai_token_usage (
    tenant_id UUID NOT NULL,
    -- First day of the UTC calendar month the counter belongs to.
    period_start DATE NOT NULL,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_ai_token_usage PRIMARY KEY (tenant_id, period_start),
    CONSTRAINT fk_ai_token_usage_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT ck_ai_token_usage_period_start CHECK (EXTRACT(DAY FROM period_start) = 1),
    CONSTRAINT ck_ai_token_usage_counts CHECK (input_tokens >= 0 AND output_tokens >= 0)
);

-- The runtime only reads and increments its own tenant's counters; it never deletes one, so usage cannot be reset
-- from the application. Resetting or correcting a counter is an operator action under the migration role.
REVOKE ALL ON TABLE dokene.ai_token_usage FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.ai_token_usage TO dokene_runtime;

ALTER TABLE dokene.ai_token_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.ai_token_usage FORCE ROW LEVEL SECURITY;

CREATE POLICY ai_token_usage_select_policy ON dokene.ai_token_usage FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY ai_token_usage_insert_policy ON dokene.ai_token_usage FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY ai_token_usage_update_policy ON dokene.ai_token_usage FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY ai_token_usage_migration_policy ON dokene.ai_token_usage FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

-- The runtime may only add to a counter: the upsert needs UPDATE, so a trigger rejects any decrease and any change of
-- tenant or month. The runtime role does not own the table, so it can neither drop nor disable the trigger; the
-- migration role, which owns it, can still correct a counter.
CREATE FUNCTION dokene.reject_ai_token_usage_decrease()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, dokene
AS $$
BEGIN
    IF current_user <> 'dokene_migration' AND (
            NEW.tenant_id <> OLD.tenant_id OR NEW.period_start <> OLD.period_start
            OR NEW.input_tokens < OLD.input_tokens OR NEW.output_tokens < OLD.output_tokens) THEN
        RAISE EXCEPTION 'AI token usage can only increase' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION dokene.reject_ai_token_usage_decrease() FROM PUBLIC;

CREATE TRIGGER ai_token_usage_only_increases
    BEFORE UPDATE ON dokene.ai_token_usage
    FOR EACH ROW EXECUTE FUNCTION dokene.reject_ai_token_usage_decrease();

-- The other branches are copied unchanged from V40; only the FAILED detail list gains TOKEN_BUDGET_EXHAUSTED.
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
