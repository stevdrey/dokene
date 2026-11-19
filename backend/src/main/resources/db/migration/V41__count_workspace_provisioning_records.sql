-- ABOUTME: Issue 312 (audit L3): lets the provisioning service count the workspaces one identity has provisioned, so
-- ABOUTME: the per-identity cap of ADR 0033 can be enforced. Identity-verified like the V17 lookup; no table grant.

-- Expand only: a backend of the previous release never calls the new function. The runtime role still has no direct
-- privilege on dokene.workspace_provisioning_records; the count is reachable only through this function, and only for
-- the identity whose signed capability is passed. A missing or forged capability raises instead of answering 0, so a
-- broken capability can never read as "no workspaces yet" and let the cap fail open.
CREATE FUNCTION dokene.count_workspace_provisioning_records(
    context_payload TEXT,
    context_signature TEXT
)
RETURNS BIGINT
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
        RAISE EXCEPTION 'Workspace provisioning capability rejected' USING ERRCODE = 'insufficient_privilege';
    END IF;

    -- Served by the leading identity_id column of uq_provisioning_identity_key.
    RETURN (
        SELECT count(*)
        FROM dokene.workspace_provisioning_records record
        WHERE record.identity_id = verified_identity_id
    );
END;
$$;

REVOKE ALL ON FUNCTION dokene.count_workspace_provisioning_records(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.count_workspace_provisioning_records(TEXT, TEXT) TO dokene_runtime;
