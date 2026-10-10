-- ABOUTME: Extends the audit log for Phase 3 outbound messaging (ADR 0023 §4.5, spec 0001): twelve closed event
-- ABOUTME: types, five constrained columns, provider attribution and the append function with defaulted parameters.

-- Closed vocabulary columns only: no body, phone, note, provider message id or other free text can be stored.
ALTER TABLE dokene.audit_events
    ADD COLUMN status_from VARCHAR(24),
    ADD COLUMN status_to VARCHAR(24),
    ADD COLUMN failure_category VARCHAR(32),
    ADD COLUMN attempt_number INTEGER,
    ADD COLUMN enabled BOOLEAN;

ALTER TABLE dokene.audit_events DROP CONSTRAINT ck_audit_shape;
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_shape CHECK (
    (event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
        AND target_type IS NULL AND target_id IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND denial_reason IS NOT NULL AND denial_reason IN (
            'NO_TENANT_CONTEXT', 'INACTIVE_MEMBERSHIP', 'MISSING_ROLE', 'MISSING_PERMISSION',
            'MISSING_RESOURCE_TENANT', 'CROSS_TENANT_RESOURCE', 'INSUFFICIENT_PERMISSION', 'UNSPECIFIED')
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

-- Every other event type keeps the five Phase 3 columns null.
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_message_columns CHECK (
    event_type IN ('MESSAGE_SUBMITTED', 'MESSAGE_APPROVED', 'MESSAGE_REJECTED', 'MESSAGE_CANCELLED',
        'MESSAGE_SEND_REQUESTED', 'MESSAGE_SENT', 'MESSAGE_SEND_FAILED', 'MESSAGE_SEND_OUTCOME_UNKNOWN',
        'MESSAGE_DELIVERY_UPDATED', 'TEMPLATE_MAPPING_UPDATED', 'INTEGRATION_UPDATED', 'OUTBOUND_KILL_SWITCH_CHANGED')
    OR (status_from IS NULL AND status_to IS NULL AND failure_category IS NULL
        AND attempt_number IS NULL AND enabled IS NULL)
);

-- Webhook driven delivery updates are attributed to the tenant with no actor and no membership (actor kind PROVIDER).
ALTER TABLE dokene.audit_events DROP CONSTRAINT ck_audit_attribution;
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_attribution CHECK (
    (tenant_id IS NOT NULL AND actor_id IS NOT NULL AND membership_id IS NOT NULL)
    OR (tenant_id IS NULL AND actor_id IS NULL AND membership_id IS NULL)
    OR (tenant_id IS NOT NULL AND actor_id IS NULL AND membership_id IS NULL
        AND event_type = 'MESSAGE_DELIVERY_UPDATED')
);

-- The append function gains the five Phase 3 columns as defaulted trailing parameters, so every existing positional
-- caller keeps resolving. The old signature is removed so there is a single writer.
DROP FUNCTION dokene.append_audit_event(UUID, TIMESTAMPTZ, VARCHAR, VARCHAR, UUID, VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, TEXT, VARCHAR, VARCHAR, VARCHAR);

CREATE FUNCTION dokene.append_audit_event(
    p_id UUID,
    p_occurred_at TIMESTAMPTZ,
    p_event_type VARCHAR(40),
    p_target_type VARCHAR(24),
    p_target_id UUID,
    p_outcome VARCHAR(8),
    p_correlation_id UUID,
    p_permission VARCHAR(40),
    p_denial_reason VARCHAR(32),
    p_previous_role VARCHAR(16),
    p_new_role VARCHAR(16),
    p_context_payload TEXT DEFAULT NULL,
    p_context_signature TEXT DEFAULT NULL,
    p_ai_operation VARCHAR(24) DEFAULT NULL,
    p_ai_outcome VARCHAR(16) DEFAULT NULL,
    p_ai_detail VARCHAR(32) DEFAULT NULL,
    p_status_from VARCHAR(24) DEFAULT NULL,
    p_status_to VARCHAR(24) DEFAULT NULL,
    p_failure_category VARCHAR(32) DEFAULT NULL,
    p_attempt_number INTEGER DEFAULT NULL,
    p_enabled BOOLEAN DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    context_parts TEXT[];
    expires_at BIGINT;
    signing_key BYTEA;
    expected_signature TEXT;
    v_tenant_id UUID;
    v_actor_id UUID;
    v_membership_id UUID;
    provider_attributed BOOLEAN;
BEGIN
    IF p_context_payload IS NULL AND p_context_signature IS NULL THEN
        IF dokene.current_verified_tenant_id() IS NOT NULL THEN
            RAISE EXCEPTION 'Global audit events require unauthenticated tenant context' USING ERRCODE = '28000';
        END IF;
        IF p_event_type <> 'AUTHORIZATION_DENIED' OR p_outcome <> 'DENIED' OR p_denial_reason <> 'NO_TENANT_CONTEXT' THEN
            RAISE EXCEPTION 'Global audit events permit only NO_TENANT_CONTEXT denial' USING ERRCODE = '28000';
        END IF;
        v_tenant_id := NULL;
        v_actor_id := NULL;
        v_membership_id := NULL;
    ELSIF p_context_payload IS NOT NULL AND p_context_signature IS NOT NULL THEN
        context_parts := string_to_array(p_context_payload, '|');
        -- 'audit' capabilities carry a member actor; 'provider' capabilities carry only the tenant and are the
        -- trusted webhook boundary's shape (ADR 0023 §4.5). Both are signed with the active tenant context key.
        IF array_length(context_parts, 1) <> 7
                OR context_parts[1] NOT IN ('audit', 'provider')
                OR context_parts[2] !~ '^[0-9a-zA-Z_-]{1,32}$'
                OR context_parts[3] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                OR context_parts[4] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                OR context_parts[5] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                OR context_parts[6] !~ '^[0-9]{10}$'
                OR context_parts[7] !~ '^[0-9a-f]{32}$'
                OR p_context_signature !~ '^[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'Invalid audit capability format' USING ERRCODE = '28000';
        END IF;
        provider_attributed := context_parts[1] = 'provider';
        IF provider_attributed AND p_event_type <> 'MESSAGE_DELIVERY_UPDATED' THEN
            RAISE EXCEPTION 'Provider audit capability permits only MESSAGE_DELIVERY_UPDATED' USING ERRCODE = '28000';
        END IF;
        IF provider_attributed AND (context_parts[4] <> '00000000-0000-0000-0000-000000000000'
                OR context_parts[5] <> '00000000-0000-0000-0000-000000000000') THEN
            RAISE EXCEPTION 'Provider audit capability carries no actor' USING ERRCODE = '28000';
        END IF;

        expires_at := context_parts[6]::BIGINT;
        IF to_timestamp(expires_at) < statement_timestamp()
                OR to_timestamp(expires_at) > statement_timestamp() + INTERVAL '70 seconds' THEN
            RAISE EXCEPTION 'Audit capability expired' USING ERRCODE = '28000';
        END IF;

        SELECT keys.signing_key
        INTO signing_key
        FROM dokene.tenant_context_signing_keys keys
        WHERE keys.key_id = context_parts[2]
            AND keys.status = 'ACTIVE';

        IF signing_key IS NULL THEN
            RAISE EXCEPTION 'Active signing key not found' USING ERRCODE = '28000';
        END IF;

        expected_signature := encode(
                dokene.hmac(convert_to(p_context_payload, 'UTF8'), signing_key, 'sha256'),
                'hex'
        );
        IF p_context_signature <> expected_signature THEN
            RAISE EXCEPTION 'Invalid audit capability signature' USING ERRCODE = '28000';
        END IF;

        v_tenant_id := context_parts[3]::UUID;
        IF v_tenant_id IS DISTINCT FROM dokene.current_verified_tenant_id() THEN
            RAISE EXCEPTION 'Audit tenant capability does not match active verified tenant' USING ERRCODE = '28000';
        END IF;

        IF provider_attributed THEN
            v_actor_id := NULL;
            v_membership_id := NULL;
        ELSE
            v_actor_id := context_parts[4]::UUID;
            v_membership_id := context_parts[5]::UUID;
        END IF;
    ELSE
        RAISE EXCEPTION 'Incomplete audit capability' USING ERRCODE = '28000';
    END IF;

    INSERT INTO dokene.audit_events (
        id, occurred_at, tenant_id, actor_id, membership_id,
        event_type, target_type, target_id, outcome,
        correlation_id, permission, denial_reason,
        previous_role, new_role,
        ai_operation, ai_outcome, ai_detail,
        status_from, status_to, failure_category, attempt_number, enabled
    ) VALUES (
        p_id, p_occurred_at, v_tenant_id, v_actor_id, v_membership_id,
        p_event_type, p_target_type, p_target_id, p_outcome,
        p_correlation_id, p_permission, p_denial_reason,
        p_previous_role, p_new_role,
        p_ai_operation, p_ai_outcome, p_ai_detail,
        p_status_from, p_status_to, p_failure_category, p_attempt_number, p_enabled
    );

    RETURN p_id;
END;
$$;

REVOKE ALL ON FUNCTION dokene.append_audit_event(UUID, TIMESTAMPTZ, VARCHAR, VARCHAR, UUID, VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, TEXT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, INTEGER, BOOLEAN) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.append_audit_event(UUID, TIMESTAMPTZ, VARCHAR, VARCHAR, UUID, VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, TEXT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, INTEGER, BOOLEAN) TO dokene_runtime;
