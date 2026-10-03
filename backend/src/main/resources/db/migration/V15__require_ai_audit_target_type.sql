-- Issue 96 review follow-up. Additive on purpose: V13 and V14 may already be applied, so they are not edited.

-- target_type is nullable and the AI branch of ck_audit_shape (V13) compares it with '=', which is UNKNOWN for NULL;
-- a CHECK accepts UNKNOWN. An AI_INVOCATION_OUTCOME row without a target type would therefore be stored and later break
-- the reader's AuditTarget mapping. Require it explicitly.
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_ai_target_type CHECK (
    event_type <> 'AI_INVOCATION_OUTCOME'
    OR (target_type IS NOT NULL AND target_type = 'CUSTOMER')
);
