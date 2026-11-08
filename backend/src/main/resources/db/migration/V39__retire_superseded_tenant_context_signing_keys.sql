-- ABOUTME: Issue 324 (audit L6): a superseded tenant-context signing key stops verifying after a grace period, so a
-- ABOUTME: rotation actually revokes the previous key. The migration job schedules and records the retirement.

-- Until now nothing ever retired a key: TenantContextSigningKeyCallback only upserted the configured key as ACTIVE,
-- so after a key-id change the previous key kept verifying forever. The callback now gives every other ACTIVE key a
-- retire_after deadline (database clock + the configured grace) and marks keys past that deadline RETIRED. The
-- verifiers below enforce the deadline themselves, on the database clock, so a superseded key stops verifying when
-- the grace ends even if the migration job never runs again.

ALTER TABLE dokene.tenant_context_signing_keys ADD COLUMN retire_after TIMESTAMPTZ;

-- Nothing wrote RETIRED before, but a hand-retired row may lack its timestamp.
UPDATE dokene.tenant_context_signing_keys
SET retired_at = statement_timestamp()
WHERE status = 'RETIRED' AND retired_at IS NULL;

UPDATE dokene.tenant_context_signing_keys
SET retired_at = NULL
WHERE status = 'ACTIVE' AND retired_at IS NOT NULL;

ALTER TABLE dokene.tenant_context_signing_keys
    ADD CONSTRAINT ck_tenant_context_signing_keys_retired_at
    CHECK ((status = 'RETIRED') = (retired_at IS NOT NULL));

-- The one place that decides whether a key may verify a capability: ACTIVE and not past its retirement deadline.
-- Security invoker on purpose: only the SECURITY DEFINER verifiers owned by dokene_migration call it, and it is not
-- executable by PUBLIC or dokene_runtime.
CREATE FUNCTION dokene.verification_signing_key(p_key_id TEXT)
RETURNS BYTEA
LANGUAGE sql
STABLE
SET search_path = pg_catalog, dokene
AS $$
    SELECT keys.signing_key
    FROM dokene.tenant_context_signing_keys keys
    WHERE keys.key_id = p_key_id
        AND keys.status = 'ACTIVE'
        AND (keys.retire_after IS NULL OR keys.retire_after > statement_timestamp());
$$;

REVOKE ALL ON FUNCTION dokene.verification_signing_key(TEXT) FROM PUBLIC;

-- Same verifier as V35; only the key lookup changed.
CREATE OR REPLACE FUNCTION dokene.signed_context_subject(
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
            OR expected_scope NOT IN ('tenant', 'identity', 'maintenance') THEN
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

    signing_key := dokene.verification_signing_key(context_parts[2]);

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

-- Same audit append as V19; only the key lookup changed. CREATE OR REPLACE keeps its grants.
CREATE OR REPLACE FUNCTION dokene.append_audit_event(
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

        signing_key := dokene.verification_signing_key(context_parts[2]);

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
