-- ABOUTME: Issue 308 (audit M4): audit rows are immutable for every role, the table owner included, and the runtime
-- ABOUTME: role may read the Flyway history so the backend can validate, not apply, the schema at startup.

-- Grants alone kept audit_events append-only only for dokene_runtime; the owner (dokene_migration) could still rewrite
-- or erase it. These triggers refuse UPDATE, DELETE and TRUNCATE whoever runs them. The only way around them is the
-- documented break-glass (ADR 0006): the table owner disables the trigger inside one explicit transaction.
CREATE FUNCTION dokene.reject_audit_event_modification()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, dokene
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % is not allowed', TG_OP USING ERRCODE = '42501';
END;
$$;

REVOKE ALL ON FUNCTION dokene.reject_audit_event_modification() FROM PUBLIC;

CREATE TRIGGER audit_events_immutable BEFORE UPDATE OR DELETE ON dokene.audit_events
    FOR EACH ROW EXECUTE FUNCTION dokene.reject_audit_event_modification();
CREATE TRIGGER audit_events_no_truncate BEFORE TRUNCATE ON dokene.audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION dokene.reject_audit_event_modification();

-- RuntimeSchemaValidationConfiguration runs Flyway validate as dokene_runtime; the history holds no tenant data.
GRANT SELECT ON TABLE dokene.flyway_schema_history TO dokene_runtime;
