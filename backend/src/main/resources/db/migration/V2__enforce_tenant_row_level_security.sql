ALTER TABLE dokene.tenant_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenant_memberships FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_memberships_select_policy
    ON dokene.tenant_memberships
    FOR SELECT
    TO dokene_runtime
    USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY tenant_memberships_insert_policy
    ON dokene.tenant_memberships
    FOR INSERT
    TO dokene_runtime
    WITH CHECK (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY tenant_memberships_update_policy
    ON dokene.tenant_memberships
    FOR UPDATE
    TO dokene_runtime
    USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);

CREATE POLICY tenant_memberships_delete_policy
    ON dokene.tenant_memberships
    FOR DELETE
    TO dokene_runtime
    USING (tenant_id = NULLIF(current_setting('dokene.current_tenant_id', true), '')::uuid);
