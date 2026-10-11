-- ABOUTME: Creates the per tenant WhatsApp integration record (spec 0005, ADR 0023 §3.3): identifiers, a secret
-- ABOUTME: reference, two switches under forced RLS; adds audit changed_fields and recreates append_audit_event.

-- One row per tenant; it never holds a secret value, only a reference under the fixed env:DOKENE_WA_ prefix.
CREATE TABLE dokene.tenant_messaging_integrations (
    tenant_id UUID PRIMARY KEY,
    provider VARCHAR(16) NOT NULL,
    phone_number_id VARCHAR(32),
    business_account_id VARCHAR(32),
    display_phone_number VARCHAR(20),
    secret_ref VARCHAR(256),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    outbound_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    webhook_verified BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_tenant_messaging_integrations_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT ck_tmi_provider CHECK (provider = 'WHATSAPP'),
    CONSTRAINT ck_tmi_phone_number_id CHECK (phone_number_id IS NULL OR phone_number_id ~ '^[0-9]{1,32}$'),
    CONSTRAINT ck_tmi_business_account_id CHECK (business_account_id IS NULL OR business_account_id ~ '^[0-9]{1,32}$'),
    CONSTRAINT ck_tmi_display_phone_number CHECK (display_phone_number IS NULL
        OR display_phone_number ~ '^\+[1-9][0-9]{1,14}$'),
    CONSTRAINT ck_tmi_secret_ref CHECK (secret_ref IS NULL OR secret_ref ~ '^env:DOKENE_WA_[A-Z0-9_]{1,100}$'),
    CONSTRAINT ck_tmi_version CHECK (version >= 1),
    CONSTRAINT ck_tmi_timestamps CHECK (updated_at >= created_at)
);

-- Unique across every tenant: one tenant can never point at another tenant's number or token.
CREATE UNIQUE INDEX ux_tmi_phone_number_id ON dokene.tenant_messaging_integrations (phone_number_id);
CREATE UNIQUE INDEX ux_tmi_secret_ref ON dokene.tenant_messaging_integrations (secret_ref);

REVOKE ALL ON TABLE dokene.tenant_messaging_integrations FROM PUBLIC;
-- No DELETE grant: a tenant disables the integration instead of removing the row.
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.tenant_messaging_integrations TO dokene_runtime;

ALTER TABLE dokene.tenant_messaging_integrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenant_messaging_integrations FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_messaging_integrations_select_policy ON dokene.tenant_messaging_integrations
    FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_messaging_integrations_insert_policy ON dokene.tenant_messaging_integrations
    FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_messaging_integrations_update_policy ON dokene.tenant_messaging_integrations
    FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_messaging_integrations_delete_policy ON dokene.tenant_messaging_integrations
    FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_messaging_integrations_migration_policy ON dokene.tenant_messaging_integrations
    FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

-- INTEGRATION_UPDATED records which fields changed as a comma joined list of closed enum names, never a value.
-- ck_audit_shape is untouched: it already requires enabled on the integration events.
ALTER TABLE dokene.audit_events ADD COLUMN changed_fields VARCHAR(160);
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_changed_fields CHECK (
    changed_fields IS NULL
    OR (event_type = 'INTEGRATION_UPDATED'
        AND changed_fields ~ '^(PHONE_NUMBER_ID|BUSINESS_ACCOUNT_ID|DISPLAY_PHONE_NUMBER|SECRET_REF|ENABLED)(,(PHONE_NUMBER_ID|BUSINESS_ACCOUNT_ID|DISPLAY_PHONE_NUMBER|SECRET_REF|ENABLED))*$')
);

-- The append function gains changed_fields as a defaulted trailing parameter, so every existing positional caller
-- keeps resolving. The V17 signature is removed so there is a single writer.
DROP FUNCTION dokene.append_audit_event(UUID, TIMESTAMPTZ, VARCHAR, VARCHAR, UUID, VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, TEXT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, INTEGER, BOOLEAN);

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
    p_enabled BOOLEAN DEFAULT NULL,
    p_changed_fields VARCHAR(160) DEFAULT NULL
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
        status_from, status_to, failure_category, attempt_number, enabled, changed_fields
    ) VALUES (
        p_id, p_occurred_at, v_tenant_id, v_actor_id, v_membership_id,
        p_event_type, p_target_type, p_target_id, p_outcome,
        p_correlation_id, p_permission, p_denial_reason,
        p_previous_role, p_new_role,
        p_ai_operation, p_ai_outcome, p_ai_detail,
        p_status_from, p_status_to, p_failure_category, p_attempt_number, p_enabled, p_changed_fields
    );

    RETURN p_id;
END;
$$;

REVOKE ALL ON FUNCTION dokene.append_audit_event(UUID, TIMESTAMPTZ, VARCHAR, VARCHAR, UUID, VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, TEXT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, INTEGER, BOOLEAN, VARCHAR) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.append_audit_event(UUID, TIMESTAMPTZ, VARCHAR, VARCHAR, UUID, VARCHAR, UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR, TEXT, TEXT, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, VARCHAR, INTEGER, BOOLEAN, VARCHAR) TO dokene_runtime;
