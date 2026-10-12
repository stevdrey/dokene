-- Tenant root and provisioning records are protected by signed-capability RLS (issue #183).
-- The runtime role may only see and change the verified tenant; it never deletes tenants.
REVOKE UPDATE, DELETE ON TABLE dokene.tenants FROM dokene_runtime;
GRANT UPDATE (display_name, status, updated_at, version) ON TABLE dokene.tenants TO dokene_runtime;

ALTER TABLE dokene.tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenants FORCE ROW LEVEL SECURITY;

CREATE POLICY tenants_select_policy
    ON dokene.tenants
    FOR SELECT
    TO dokene_runtime
    USING (id = dokene.current_verified_tenant_id());

CREATE POLICY tenants_insert_policy
    ON dokene.tenants
    FOR INSERT
    TO dokene_runtime
    WITH CHECK (id = dokene.current_verified_tenant_id());

CREATE POLICY tenants_update_policy
    ON dokene.tenants
    FOR UPDATE
    TO dokene_runtime
    USING (id = dokene.current_verified_tenant_id())
    WITH CHECK (id = dokene.current_verified_tenant_id());

CREATE POLICY tenants_migration_policy
    ON dokene.tenants
    FOR ALL
    TO dokene_migration
    USING (true)
    WITH CHECK (true);

ALTER TABLE dokene.workspace_provisioning_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.workspace_provisioning_records FORCE ROW LEVEL SECURITY;

CREATE POLICY workspace_provisioning_records_select_policy
    ON dokene.workspace_provisioning_records
    FOR SELECT
    TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());

CREATE POLICY workspace_provisioning_records_insert_policy
    ON dokene.workspace_provisioning_records
    FOR INSERT
    TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());

CREATE POLICY workspace_provisioning_records_migration_policy
    ON dokene.workspace_provisioning_records
    FOR ALL
    TO dokene_migration
    USING (true)
    WITH CHECK (true);

-- Idempotent provisioning lookup happens before any tenant is bound, so it is keyed on the
-- identity-scoped signed capability and returns only the caller's own record.
CREATE FUNCTION dokene.find_workspace_provisioning_record(
    context_payload TEXT,
    context_signature TEXT,
    requested_idempotency_key TEXT
)
RETURNS TABLE (
    id UUID,
    tenant_id UUID,
    display_name VARCHAR,
    created_at TIMESTAMP WITH TIME ZONE
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
    IF verified_identity_id IS NULL OR requested_idempotency_key IS NULL THEN
        RETURN;
    END IF;

    RETURN QUERY
        SELECT record.id, record.tenant_id, record.display_name, record.created_at
        FROM dokene.workspace_provisioning_records record
        WHERE record.identity_id = verified_identity_id
            AND record.idempotency_key = requested_idempotency_key;
END;
$$;

REVOKE ALL ON FUNCTION dokene.find_workspace_provisioning_record(TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.find_workspace_provisioning_record(TEXT, TEXT, TEXT) TO dokene_runtime;
