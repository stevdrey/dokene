-- ABOUTME: Follow-up scheduler tenant rotation (issue 463, ADR 0040): the only cross-tenant read the scheduler makes,
-- ABOUTME: returning active tenant ids one keyset page at a time and nothing else. Additive only.

-- The scheduler starts outside any tenant scope, so tenant RLS hides every tenant row from it. Like the other
-- maintenance finders (V35), this function needs a short-lived capability signed for its own task, returns ids only,
-- and bounds its page. The scheduler then binds each tenant itself through the signed tenant context, so forced RLS
-- still applies to every candidate and work item it touches. Pages are keyed by tenant id after the caller's cursor,
-- so every active tenant is reached in bounded steps whatever happens to the tenants before it.
CREATE FUNCTION dokene.find_follow_up_scheduling_tenants(capability_payload TEXT, capability_signature TEXT,
    after_tenant_id UUID, max_rows INTEGER)
RETURNS TABLE (tenant_id UUID)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
BEGIN
    IF max_rows IS NULL OR max_rows < 1
            OR NOT dokene.has_maintenance_capability(capability_payload, capability_signature,
                '00000000-0000-0000-0000-000000000004') THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT tenants.id
    FROM dokene.tenants AS tenants
    WHERE tenants.status = 'ACTIVE'
        AND (after_tenant_id IS NULL OR tenants.id > after_tenant_id)
    ORDER BY tenants.id
    LIMIT LEAST(max_rows, 500);
END;
$$;

REVOKE ALL ON FUNCTION dokene.find_follow_up_scheduling_tenants(TEXT, TEXT, UUID, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.find_follow_up_scheduling_tenants(TEXT, TEXT, UUID, INTEGER) TO dokene_runtime;
