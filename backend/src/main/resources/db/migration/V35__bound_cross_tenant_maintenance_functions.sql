-- ABOUTME: Issue 313 (audit L4): the cross-tenant maintenance functions require a signed maintenance capability and
-- ABOUTME: clamp their cutoffs to the database clock, so the runtime role alone can neither list ids early nor purge.

-- The inbox sweeper, the retention purge and dispatch recovery run without a tenant context, so their functions bypass
-- tenant RLS by design. Until now any dokene_runtime session could call them with any cutoff. They now take a
-- short-lived capability signed with the tenant-context key, which only the backend holds, like the identity-scope
-- functions (V3, V16, V17); a statement injected into a request or a runtime credential without the key cannot forge
-- one. Their cutoffs are also clamped here, so even a valid caller cannot purge inside the deduplication window or
-- list rows that are not yet due.

-- Same verifier as V3 with one more scope. Tenant RLS and the identity functions still ask for their own scopes, so a
-- maintenance capability never satisfies them, and neither of theirs satisfies the maintenance check.
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

-- A maintenance capability names no tenant or identity: its subject is the nil UUID. Not callable by the runtime role.
CREATE FUNCTION dokene.has_maintenance_capability(capability_payload TEXT, capability_signature TEXT)
RETURNS BOOLEAN
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
    SELECT coalesce(dokene.signed_context_subject(capability_payload, capability_signature, 'maintenance')
        = '00000000-0000-0000-0000-000000000000'::UUID, false);
$$;

REVOKE ALL ON FUNCTION dokene.has_maintenance_capability(TEXT, TEXT) FROM PUBLIC;

DROP FUNCTION dokene.find_expired_dispatch_claims(TIMESTAMPTZ, UUID, INTEGER);
DROP FUNCTION dokene.find_due_webhook_events(TIMESTAMPTZ, INTEGER);
DROP FUNCTION dokene.purge_webhook_events(TIMESTAMPTZ, INTEGER);

-- Dispatch recovery (ADR 0028 §5). Enabled recovery requires claim-timeout >= send-timeout + 1 minute, so a claim
-- started less than a minute ago is never expired; the floor keeps in-flight claims out of the result.
CREATE FUNCTION dokene.find_expired_dispatch_claims(capability_payload TEXT, capability_signature TEXT,
    started_before TIMESTAMPTZ, after_attempt_id UUID, max_rows INTEGER)
RETURNS TABLE (tenant_id UUID, message_id UUID, attempt_id UUID)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    cutoff TIMESTAMPTZ;
BEGIN
    IF started_before IS NULL OR max_rows IS NULL OR max_rows < 1
            OR NOT dokene.has_maintenance_capability(capability_payload, capability_signature) THEN
        RETURN;
    END IF;
    cutoff := LEAST(started_before, statement_timestamp() - INTERVAL '1 minute');

    RETURN QUERY
    SELECT attempts.tenant_id, attempts.message_id, attempts.id
    FROM dokene.outbound_send_attempts AS attempts
    WHERE attempts.outcome = 'STARTED' AND attempts.started_at < cutoff
        AND (after_attempt_id IS NULL OR attempts.id > after_attempt_id)
    ORDER BY attempts.id
    LIMIT LEAST(max_rows, 500);
END;
$$;

REVOKE ALL ON FUNCTION dokene.find_expired_dispatch_claims(TEXT, TEXT, TIMESTAMPTZ, UUID, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.find_expired_dispatch_claims(TEXT, TEXT, TIMESTAMPTZ, UUID, INTEGER)
    TO dokene_runtime;

-- Inbox sweeper (ADR 0030): nothing is due later than now.
CREATE FUNCTION dokene.find_due_webhook_events(capability_payload TEXT, capability_signature TEXT,
    due_at TIMESTAMPTZ, max_rows INTEGER)
RETURNS TABLE (tenant_id UUID, event_id UUID)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    cutoff TIMESTAMPTZ;
BEGIN
    IF due_at IS NULL OR max_rows IS NULL OR max_rows < 1
            OR NOT dokene.has_maintenance_capability(capability_payload, capability_signature) THEN
        RETURN;
    END IF;
    cutoff := LEAST(due_at, statement_timestamp());

    RETURN QUERY
    SELECT events.tenant_id, events.id
    FROM dokene.provider_webhook_events AS events
    WHERE events.state = 'PENDING' AND events.next_attempt_at <= cutoff
    ORDER BY events.next_attempt_at, events.id
    LIMIT LEAST(max_rows, 500);
END;
$$;

REVOKE ALL ON FUNCTION dokene.find_due_webhook_events(TEXT, TEXT, TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.find_due_webhook_events(TEXT, TEXT, TIMESTAMPTZ, INTEGER) TO dokene_runtime;

-- Retention purge (ADR 0030). The configured retention is at least 8 days (beyond Meta's 7 day retries), so a row
-- processed more recently is never purged, whatever cutoff the caller passes.
CREATE FUNCTION dokene.purge_webhook_events(capability_payload TEXT, capability_signature TEXT,
    processed_before TIMESTAMPTZ, max_rows INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    cutoff TIMESTAMPTZ;
    purged INTEGER;
BEGIN
    IF processed_before IS NULL OR max_rows IS NULL OR max_rows < 1
            OR NOT dokene.has_maintenance_capability(capability_payload, capability_signature) THEN
        RETURN 0;
    END IF;
    cutoff := LEAST(processed_before, statement_timestamp() - INTERVAL '8 days');

    DELETE FROM dokene.provider_webhook_events
    WHERE id IN (
        SELECT events.id
        FROM dokene.provider_webhook_events AS events
        WHERE events.state <> 'PENDING' AND events.processed_at < cutoff
        ORDER BY events.processed_at
        LIMIT LEAST(max_rows, 5000));
    GET DIAGNOSTICS purged = ROW_COUNT;
    RETURN purged;
END;
$$;

REVOKE ALL ON FUNCTION dokene.purge_webhook_events(TEXT, TEXT, TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.purge_webhook_events(TEXT, TEXT, TIMESTAMPTZ, INTEGER) TO dokene_runtime;
