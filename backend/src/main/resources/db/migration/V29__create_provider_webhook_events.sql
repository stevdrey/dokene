-- ABOUTME: Durable webhook inbox (issue 139, ADR 0030): one typed row per verified provider status, deduplicated on
-- ABOUTME: (provider, provider_event_id), tenant RLS forced, plus the two id-only cross-tenant functions the sweeper needs.

-- A row holds only the typed facts parsed from a verified, schema valid callback for a verified mapping: never the raw
-- provider JSON, error texts, recipient phone, conversation or pricing data. PENDING rows are applied by the inbox
-- processor; NotFound reports stay PENDING for a bounded park window (the race with T8 committing the provider id)
-- and then become UNMATCHED with the provider message id cleared. Terminal rows are kept only for deduplication until
-- the retention purge removes them.
CREATE TABLE dokene.provider_webhook_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    provider VARCHAR(24) NOT NULL,
    provider_event_id VARCHAR(128) NOT NULL,
    provider_message_id VARCHAR(128),
    delivery_status VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    failure_category VARCHAR(32),
    state VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    received_at TIMESTAMPTZ NOT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT ck_provider_webhook_events_provider CHECK (provider IN ('META_WHATSAPP')),
    CONSTRAINT ck_provider_webhook_events_event_id CHECK (length(btrim(provider_event_id)) > 0),
    CONSTRAINT ck_provider_webhook_events_status CHECK (delivery_status IN ('SENT', 'DELIVERED', 'READ', 'FAILED')),
    CONSTRAINT ck_provider_webhook_events_failure CHECK (
        (failure_category IS NULL OR delivery_status = 'FAILED')
        AND (failure_category IS NULL OR failure_category IN ('INVALID_RECIPIENT', 'TEMPLATE_REJECTED',
            'RATE_LIMITED', 'PROVIDER_UNAVAILABLE', 'AUTHENTICATION', 'POLICY_VIOLATION', 'UNKNOWN'))),
    CONSTRAINT ck_provider_webhook_events_state CHECK (state IN ('PENDING', 'APPLIED', 'IGNORED', 'UNMATCHED')),
    CONSTRAINT ck_provider_webhook_events_message_id CHECK (
        (state = 'UNMATCHED') = (provider_message_id IS NULL)),
    CONSTRAINT ck_provider_webhook_events_processed CHECK ((state = 'PENDING') = (processed_at IS NULL)),
    CONSTRAINT ck_provider_webhook_events_attempts CHECK (attempts >= 0),
    CONSTRAINT uq_provider_webhook_events_event UNIQUE (provider, provider_event_id)
);

CREATE INDEX provider_webhook_events_due ON dokene.provider_webhook_events(next_attempt_at)
    WHERE state = 'PENDING';
CREATE INDEX provider_webhook_events_processed ON dokene.provider_webhook_events(processed_at)
    WHERE state <> 'PENDING';
CREATE INDEX provider_webhook_events_tenant ON dokene.provider_webhook_events(tenant_id);

REVOKE ALL ON TABLE dokene.provider_webhook_events FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.provider_webhook_events TO dokene_runtime;

ALTER TABLE dokene.provider_webhook_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.provider_webhook_events FORCE ROW LEVEL SECURITY;

CREATE POLICY provider_webhook_events_select_policy ON dokene.provider_webhook_events FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY provider_webhook_events_insert_policy ON dokene.provider_webhook_events FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY provider_webhook_events_update_policy ON dokene.provider_webhook_events FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY provider_webhook_events_delete_policy ON dokene.provider_webhook_events FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY provider_webhook_events_migration_policy ON dokene.provider_webhook_events FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

-- The inbox sweeper starts without a tenant context, so RLS hides every row from it. This returns ids and owning
-- tenants of due PENDING rows only, bounded by the caller's limit; each row is then processed under its own tenant.
CREATE FUNCTION dokene.find_due_webhook_events(due_at TIMESTAMPTZ, max_rows INTEGER)
RETURNS TABLE (tenant_id UUID, event_id UUID)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
BEGIN
    IF due_at IS NULL OR max_rows IS NULL OR max_rows < 1 THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT events.tenant_id, events.id
    FROM dokene.provider_webhook_events AS events
    WHERE events.state = 'PENDING' AND events.next_attempt_at <= due_at
    ORDER BY events.next_attempt_at, events.id
    LIMIT LEAST(max_rows, 500);
END;
$$;

REVOKE ALL ON FUNCTION dokene.find_due_webhook_events(TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.find_due_webhook_events(TIMESTAMPTZ, INTEGER) TO dokene_runtime;

-- Retention purge: deletes at most max_rows terminal rows processed before the cutoff and returns how many. PENDING
-- rows are never purged, and the runtime role has no DELETE grant on the table, so this is the only way rows leave.
CREATE FUNCTION dokene.purge_webhook_events(processed_before TIMESTAMPTZ, max_rows INTEGER)
RETURNS INTEGER
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    purged INTEGER;
BEGIN
    IF processed_before IS NULL OR max_rows IS NULL OR max_rows < 1 THEN
        RETURN 0;
    END IF;

    DELETE FROM dokene.provider_webhook_events
    WHERE id IN (
        SELECT events.id
        FROM dokene.provider_webhook_events AS events
        WHERE events.state <> 'PENDING' AND events.processed_at < processed_before
        ORDER BY events.processed_at
        LIMIT LEAST(max_rows, 5000));
    GET DIAGNOSTICS purged = ROW_COUNT;
    RETURN purged;
END;
$$;

REVOKE ALL ON FUNCTION dokene.purge_webhook_events(TIMESTAMPTZ, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.purge_webhook_events(TIMESTAMPTZ, INTEGER) TO dokene_runtime;
