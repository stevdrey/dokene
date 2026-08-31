CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA dokene;

CREATE TABLE dokene.tenant_context_signing_keys (
    key_id VARCHAR(32) PRIMARY KEY,
    signing_key BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT statement_timestamp(),
    retired_at TIMESTAMPTZ,
    CONSTRAINT ck_tenant_context_signing_keys_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT ck_tenant_context_signing_keys_length CHECK (octet_length(signing_key) = 32),
    CONSTRAINT ck_tenant_context_signing_keys_id_format CHECK (key_id ~ '^[0-9a-zA-Z_-]{1,32}$')
);

REVOKE ALL ON TABLE dokene.tenant_context_signing_keys FROM PUBLIC;

CREATE FUNCTION dokene.signed_context_subject(
    context_payload TEXT,
    context_signature TEXT,
    expected_scope TEXT
)
RETURNS UUID
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    context_parts TEXT[];
    expires_at BIGINT;
    signing_key BYTEA;
    expected_signature TEXT;
BEGIN
    IF context_payload IS NULL OR context_signature IS NULL
            OR expected_scope NOT IN ('tenant', 'identity') THEN
        RETURN NULL;
    END IF;

    context_parts := string_to_array(context_payload, '|');
    IF array_length(context_parts, 1) <> 5
            OR context_parts[1] <> expected_scope
            OR context_parts[2] !~ '^[0-9a-zA-Z_-]{1,32}$'
            OR context_parts[3] !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
            OR context_parts[4] !~ '^[0-9]{10}$'
            OR context_parts[5] !~ '^[0-9a-f]{32}$'
            OR context_signature !~ '^[0-9a-f]{64}$' THEN
        RETURN NULL;
    END IF;

    expires_at := context_parts[4]::BIGINT;
    IF to_timestamp(expires_at) < statement_timestamp()
            OR to_timestamp(expires_at) > statement_timestamp() + INTERVAL '70 seconds' THEN
        RETURN NULL;
    END IF;

    SELECT keys.signing_key
    INTO signing_key
    FROM dokene.tenant_context_signing_keys keys
    WHERE keys.key_id = context_parts[2]
        AND keys.status = 'ACTIVE';

    IF signing_key IS NULL THEN
        RETURN NULL;
    END IF;

    expected_signature := encode(
            dokene.hmac(convert_to(context_payload, 'UTF8'), signing_key, 'sha256'),
            'hex'
    );
    IF context_signature <> expected_signature THEN
        RETURN NULL;
    END IF;

    RETURN context_parts[3]::UUID;
END;
$$;

CREATE FUNCTION dokene.current_verified_tenant_id()
RETURNS UUID
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
    SELECT dokene.signed_context_subject(
        current_setting('dokene.tenant_context', TRUE),
        current_setting('dokene.tenant_context_signature', TRUE),
        'tenant'
    );
$$;

CREATE FUNCTION dokene.discover_active_tenant_memberships(
    context_payload TEXT,
    context_signature TEXT
)
RETURNS TABLE (
    tenant_id UUID,
    tenant_display_name VARCHAR,
    membership_id UUID,
    role VARCHAR
)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    verified_identity_id UUID;
BEGIN
    verified_identity_id := dokene.signed_context_subject(context_payload, context_signature, 'identity');
    IF verified_identity_id IS NULL THEN
        RETURN;
    END IF;

    RETURN QUERY
        SELECT tenant.id, tenant.display_name, membership.id, membership.role
        FROM dokene.tenant_memberships membership
        JOIN dokene.tenants tenant ON tenant.id = membership.tenant_id
        WHERE membership.identity_id = verified_identity_id
            AND membership.status = 'ACTIVE'
            AND tenant.status = 'ACTIVE'
        ORDER BY tenant.display_name, tenant.id;
END;
$$;

REVOKE ALL ON FUNCTION dokene.signed_context_subject(TEXT, TEXT, TEXT) FROM PUBLIC;
REVOKE ALL ON FUNCTION dokene.current_verified_tenant_id() FROM PUBLIC;
REVOKE ALL ON FUNCTION dokene.discover_active_tenant_memberships(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.current_verified_tenant_id() TO dokene_runtime;
GRANT EXECUTE ON FUNCTION dokene.discover_active_tenant_memberships(TEXT, TEXT) TO dokene_runtime;

ALTER POLICY tenant_memberships_select_policy
    ON dokene.tenant_memberships
    USING (tenant_id = dokene.current_verified_tenant_id());

ALTER POLICY tenant_memberships_insert_policy
    ON dokene.tenant_memberships
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());

ALTER POLICY tenant_memberships_update_policy
    ON dokene.tenant_memberships
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());

ALTER POLICY tenant_memberships_delete_policy
    ON dokene.tenant_memberships
    USING (tenant_id = dokene.current_verified_tenant_id());
