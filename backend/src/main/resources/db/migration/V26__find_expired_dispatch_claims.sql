-- ABOUTME: Dispatch recovery (issue 138, ADR 0028 §5): the only cross-tenant read of send attempts, returning the
-- ABOUTME: ids of STARTED claims older than a cutoff, one keyset page at a time, and nothing else. Additive only.

-- The recovery processor has no tenant context when it looks for expired claims; tenant RLS hides every attempt row
-- from it. This function returns ids only, bounded by the caller's limit, and the processor then works on each claim
-- under that claim's own tenant. The cutoff comes from the application clock so the claim timeout stays in one place.
-- Pages are keyed by attempt id after the caller's cursor, not oldest first: a claim the processor must leave alone
-- (its member was revoked) stays STARTED, and an oldest-first page would return it on every run and starve the rest.
CREATE FUNCTION dokene.find_expired_dispatch_claims(started_before TIMESTAMPTZ, after_attempt_id UUID,
    max_rows INTEGER)
RETURNS TABLE (tenant_id UUID, message_id UUID, attempt_id UUID)
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
BEGIN
    IF started_before IS NULL OR max_rows IS NULL OR max_rows < 1 THEN
        RETURN;
    END IF;

    RETURN QUERY
    SELECT attempts.tenant_id, attempts.message_id, attempts.id
    FROM dokene.outbound_send_attempts AS attempts
    WHERE attempts.outcome = 'STARTED' AND attempts.started_at < started_before
        AND (after_attempt_id IS NULL OR attempts.id > after_attempt_id)
    ORDER BY attempts.id
    LIMIT LEAST(max_rows, 500);
END;
$$;

REVOKE ALL ON FUNCTION dokene.find_expired_dispatch_claims(TIMESTAMPTZ, UUID, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.find_expired_dispatch_claims(TIMESTAMPTZ, UUID, INTEGER) TO dokene_runtime;
