CREATE TABLE dokene.audit_events (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    tenant_id UUID,
    actor_id UUID,
    membership_id UUID,
    event_type VARCHAR(40) NOT NULL,
    target_type VARCHAR(24),
    target_id UUID,
    outcome VARCHAR(8) NOT NULL,
    correlation_id UUID NOT NULL,
    permission VARCHAR(40),
    denial_reason VARCHAR(32),
    previous_role VARCHAR(16),
    new_role VARCHAR(16),
    CONSTRAINT ck_audit_attribution CHECK (
        (tenant_id IS NOT NULL AND actor_id IS NOT NULL AND membership_id IS NOT NULL)
        OR (tenant_id IS NULL AND actor_id IS NULL AND membership_id IS NULL)
    ),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    CONSTRAINT ck_audit_shape CHECK (
        (event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
            AND target_type IS NULL AND target_id IS NULL
            AND previous_role IS NULL AND new_role IS NULL
            AND denial_reason IS NOT NULL AND denial_reason IN (
                'NO_TENANT_CONTEXT', 'INACTIVE_MEMBERSHIP', 'MISSING_ROLE', 'MISSING_PERMISSION',
                'MISSING_RESOURCE_TENANT', 'CROSS_TENANT_RESOURCE', 'INSUFFICIENT_PERMISSION', 'UNSPECIFIED')
            AND ((tenant_id IS NULL AND denial_reason = 'NO_TENANT_CONTEXT')
                OR (tenant_id IS NOT NULL AND denial_reason <> 'NO_TENANT_CONTEXT')))
        OR (event_type = 'MEMBERSHIP_ROLE_CHANGED' AND outcome = 'SUCCESS'
            AND tenant_id IS NOT NULL AND target_type IS NOT NULL AND target_type = 'MEMBERSHIP'
            AND target_id IS NOT NULL AND permission IS NULL AND denial_reason IS NULL
            AND previous_role IS NOT NULL AND previous_role IN ('ADMIN', 'OPERATOR', 'VIEWER')
            AND new_role IS NOT NULL AND new_role IN ('ADMIN', 'OPERATOR', 'VIEWER')
            AND previous_role <> new_role)
    ),
    CONSTRAINT ck_audit_permission CHECK (permission IS NULL OR permission IN (
        'TENANT_READ', 'TENANT_UPDATE', 'TENANT_ARCHIVE',
        'MEMBERSHIP_READ', 'MEMBERSHIP_INVITE', 'MEMBERSHIP_ROLE_UPDATE', 'MEMBERSHIP_REVOKE',
        'CUSTOMER_READ', 'CUSTOMER_WRITE', 'CUSTOMER_DELETE',
        'FOLLOWUP_READ', 'FOLLOWUP_WRITE', 'FOLLOWUP_EVALUATE',
        'TEMPLATE_READ', 'TEMPLATE_WRITE',
        'MESSAGE_READ', 'MESSAGE_DRAFT', 'MESSAGE_APPROVE', 'MESSAGE_SEND',
        'INTEGRATION_READ', 'INTEGRATION_MANAGE', 'AUDIT_READ', 'DATA_EXPORT'
    ))
);

-- Historical references intentionally have no cascading foreign keys.
CREATE INDEX audit_events_tenant_chronology ON dokene.audit_events (tenant_id, occurred_at DESC, id DESC);

REVOKE ALL ON TABLE dokene.audit_events FROM PUBLIC, dokene_runtime;
GRANT SELECT ON TABLE dokene.audit_events TO dokene_runtime;
ALTER TABLE dokene.audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_events_select ON dokene.audit_events FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
-- No runtime INSERT, UPDATE, or DELETE privileges; no TRUNCATE, ownership, or RLS-bypass privileges.
CREATE POLICY audit_events_migration ON dokene.audit_events FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

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
    p_context_signature TEXT DEFAULT NULL
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
        IF array_length(context_parts, 1) <> 7
                OR context_parts[1] <> 'audit'
                OR context_parts[2] !~ '^[0-9a-zA-Z_-]{1,32}$'
                OR context_parts[3] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                OR context_parts[4] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                OR context_parts[5] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
                OR context_parts[6] !~ '^[0-9]{10}$'
                OR context_parts[7] !~ '^[0-9a-f]{32}$'
                OR p_context_signature !~ '^[0-9a-f]{64}$' THEN
            RAISE EXCEPTION 'Invalid audit capability format' USING ERRCODE = '28000';
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

        v_actor_id := context_parts[4]::UUID;
        v_membership_id := context_parts[5]::UUID;
    ELSE
        RAISE EXCEPTION 'Incomplete audit capability' USING ERRCODE = '28000';
    END IF;

    INSERT INTO dokene.audit_events (
        id, occurred_at, tenant_id, actor_id, membership_id,
        event_type, target_type, target_id, outcome,
        correlation_id, permission, denial_reason,
        previous_role, new_role
    ) VALUES (
        p_id, p_occurred_at, v_tenant_id, v_actor_id, v_membership_id,
        p_event_type, p_target_type, p_target_id, p_outcome,
        p_correlation_id, p_permission, p_denial_reason,
        p_previous_role, p_new_role
    );

    RETURN p_id;
END;
$$;

REVOKE ALL ON FUNCTION dokene.append_audit_event(UUID, TIMESTAMPTZ, VARCHAR, VARCHAR, UUID, VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.append_audit_event(UUID, TIMESTAMPTZ, VARCHAR, VARCHAR, UUID, VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, TEXT) TO dokene_runtime;
