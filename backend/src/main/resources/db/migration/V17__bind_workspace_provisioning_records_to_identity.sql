-- Workspace provisioning records are written and read only through identity-verified functions
-- (issue #183 review): the V16 tenant-scoped INSERT policy did not bind identity_id, so a tenant
-- context could record a mapping for an arbitrary identity.
REVOKE SELECT, INSERT ON TABLE dokene.workspace_provisioning_records FROM dokene_runtime;

DROP POLICY workspace_provisioning_records_select_policy ON dokene.workspace_provisioning_records;
DROP POLICY workspace_provisioning_records_insert_policy ON dokene.workspace_provisioning_records;

DROP FUNCTION dokene.find_workspace_provisioning_record(TEXT, TEXT, TEXT);

CREATE FUNCTION dokene.find_workspace_provisioning_record(
    context_payload TEXT,
    context_signature TEXT,
    requested_idempotency_key TEXT
)
RETURNS TABLE (
    id UUID,
    idempotency_key VARCHAR,
    identity_id UUID,
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
        SELECT record.id, record.idempotency_key, record.identity_id, record.tenant_id,
            record.display_name, record.created_at
        FROM dokene.workspace_provisioning_records record
        WHERE record.identity_id = verified_identity_id
            AND record.idempotency_key = requested_idempotency_key;
END;
$$;

-- Requires both the identity-scoped capability of the provisioning caller and the signed tenant
-- context of the workspace being recorded.
CREATE FUNCTION dokene.record_workspace_provisioning(
    context_payload TEXT,
    context_signature TEXT,
    record_id UUID,
    requested_idempotency_key TEXT,
    requested_tenant_id UUID,
    requested_display_name TEXT,
    requested_created_at TIMESTAMP WITH TIME ZONE
)
RETURNS VOID
LANGUAGE plpgsql
VOLATILE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    verified_identity_id UUID;
BEGIN
    verified_identity_id := dokene.signed_context_subject(context_payload, context_signature, 'identity');
    IF verified_identity_id IS NULL
            OR requested_tenant_id IS NULL
            OR requested_tenant_id IS DISTINCT FROM dokene.current_verified_tenant_id() THEN
        RAISE EXCEPTION 'Workspace provisioning capability rejected' USING ERRCODE = 'insufficient_privilege';
    END IF;

    INSERT INTO dokene.workspace_provisioning_records (
        id, idempotency_key, identity_id, tenant_id, display_name, created_at
    ) VALUES (
        record_id, requested_idempotency_key, verified_identity_id, requested_tenant_id,
        requested_display_name, requested_created_at
    );
END;
$$;

REVOKE ALL ON FUNCTION dokene.find_workspace_provisioning_record(TEXT, TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.find_workspace_provisioning_record(TEXT, TEXT, TEXT) TO dokene_runtime;
REVOKE ALL ON FUNCTION dokene.record_workspace_provisioning(TEXT, TEXT, UUID, TEXT, UUID, TEXT, TIMESTAMP WITH TIME ZONE) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.record_workspace_provisioning(TEXT, TEXT, UUID, TEXT, UUID, TEXT, TIMESTAMP WITH TIME ZONE) TO dokene_runtime;
