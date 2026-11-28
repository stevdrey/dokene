-- ABOUTME: Issue 459 (ADR 0040): the shared admission budget. One row per admitted send or candidate per tenant, one
-- ABOUTME: timestamp per admitted send for the platform wide count, and one pause row per tenant the provider throttled.

-- Expand only: a backend of the previous release never reads or writes these tables.
CREATE TABLE dokene.tenant_budget_admissions (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    tenant_id UUID NOT NULL,
    kind TEXT NOT NULL,
    admitted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_tenant_budget_admissions PRIMARY KEY (id),
    CONSTRAINT fk_tenant_budget_admissions_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_budget_admissions_kind CHECK (kind IN ('SEND', 'CANDIDATE'))
);

CREATE INDEX ix_tenant_budget_admissions_window
    ON dokene.tenant_budget_admissions (tenant_id, kind, admitted_at);

-- Platform state: timestamps only and no tenant_id, so the global count never reads a tenant's rows and this table
-- tells nothing about who sent.
CREATE TABLE dokene.platform_send_admissions (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    admitted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_platform_send_admissions PRIMARY KEY (id)
);

CREATE INDEX ix_platform_send_admissions_window ON dokene.platform_send_admissions (admitted_at);

CREATE TABLE dokene.tenant_send_pauses (
    tenant_id UUID NOT NULL,
    paused_until TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_tenant_send_pauses PRIMARY KEY (tenant_id),
    CONSTRAINT fk_tenant_send_pauses_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT
);

REVOKE ALL ON TABLE dokene.tenant_budget_admissions FROM PUBLIC;
REVOKE ALL ON TABLE dokene.platform_send_admissions FROM PUBLIC;
REVOKE ALL ON TABLE dokene.tenant_send_pauses FROM PUBLIC;
GRANT SELECT, INSERT, DELETE ON TABLE dokene.tenant_budget_admissions TO dokene_runtime;
GRANT SELECT, INSERT, DELETE ON TABLE dokene.platform_send_admissions TO dokene_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.tenant_send_pauses TO dokene_runtime;

ALTER TABLE dokene.tenant_budget_admissions ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenant_budget_admissions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_budget_admissions_select_policy ON dokene.tenant_budget_admissions
    FOR SELECT TO dokene_runtime USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_budget_admissions_insert_policy ON dokene.tenant_budget_admissions
    FOR INSERT TO dokene_runtime WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
-- Without this policy the prune would delete nothing and raise no error.
CREATE POLICY tenant_budget_admissions_delete_policy ON dokene.tenant_budget_admissions
    FOR DELETE TO dokene_runtime USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_budget_admissions_migration_policy ON dokene.tenant_budget_admissions
    FOR ALL TO dokene_migration USING (true) WITH CHECK (true);

ALTER TABLE dokene.tenant_send_pauses ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenant_send_pauses FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_send_pauses_select_policy ON dokene.tenant_send_pauses
    FOR SELECT TO dokene_runtime USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_send_pauses_insert_policy ON dokene.tenant_send_pauses
    FOR INSERT TO dokene_runtime WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_send_pauses_update_policy ON dokene.tenant_send_pauses
    FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_send_pauses_migration_policy ON dokene.tenant_send_pauses
    FOR ALL TO dokene_migration USING (true) WITH CHECK (true);
