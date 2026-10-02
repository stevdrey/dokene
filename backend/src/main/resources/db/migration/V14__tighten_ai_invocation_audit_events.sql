-- Issue 96 review follow-up. Additive on purpose: V13 may already be applied, so it is not edited.

-- A CHECK passes when its expression is UNKNOWN. The AI branch of ck_audit_shape (V13) compares the AI columns with
-- IN/= and therefore accepts NULLs, which would let an AI_INVOCATION_OUTCOME row skip the closed vocabulary and later
-- break reads. Require all three columns explicitly.
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_ai_not_null CHECK (
    event_type <> 'AI_INVOCATION_OUTCOME'
    OR (ai_operation IS NOT NULL AND ai_outcome IS NOT NULL AND ai_detail IS NOT NULL)
);

-- NO_TENANT_CONTEXT, UNAUTHORIZED and CUSTOMER_NOT_FOUND are raised at the authorization/existence boundary, before
-- the requested customer is known to belong to the active tenant. The audit target would then be an unverified, possibly
-- foreign-tenant identifier (ADR 0006), so these details can never be stored.
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_ai_detail_verified_target CHECK (
    ai_detail IS NULL OR ai_detail NOT IN ('NO_TENANT_CONTEXT', 'UNAUTHORIZED', 'CUSTOMER_NOT_FOUND')
);
