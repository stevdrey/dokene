-- ABOUTME: Creates the tenant template registry (ADR 0023 §4.7, ADR 0025, issue 135): one mapping per closed semantic
-- ABOUTME: intent plus provider-derived approval evidence, under forced RLS and bound to the tenant's own integration.

CREATE TABLE dokene.tenant_provider_templates (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    integration_id UUID NOT NULL,
    semantic_intent VARCHAR(32) NOT NULL,
    provider_template_name VARCHAR(512) NOT NULL,
    language VARCHAR(8) NOT NULL,
    parameter_names TEXT[] NOT NULL,
    enabled BOOLEAN NOT NULL,
    approval_status VARCHAR(16) NOT NULL DEFAULT 'UNVERIFIED',
    approval_checked_at TIMESTAMPTZ,
    approval_checked_business_account_id VARCHAR(32),
    approval_checked_version BIGINT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- The mapping revision the approval fingerprint binds; advances only when the editable mapping changes.
    version BIGINT NOT NULL DEFAULT 0,
    -- One mapping per intent and tenant; the service creates it once and then replaces it by version.
    CONSTRAINT uq_tenant_provider_templates_tenant_intent UNIQUE (tenant_id, semantic_intent),
    CONSTRAINT fk_tenant_provider_templates_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    -- A mapping can only point at an integration of the same tenant.
    CONSTRAINT fk_tenant_provider_templates_integration FOREIGN KEY (tenant_id, integration_id)
        REFERENCES dokene.tenant_messaging_integrations(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_provider_templates_intent CHECK (semantic_intent IN (
        'GENERAL_FOLLOW_UP', 'REPEAT_PURCHASE', 'RELATED_PRODUCT', 'SEASONAL_EVENT', 'DORMANT_CUSTOMER')),
    CONSTRAINT ck_tenant_provider_templates_name CHECK (
        provider_template_name ~ '^[a-z0-9_]+$' AND length(provider_template_name) <= 512),
    CONSTRAINT ck_tenant_provider_templates_language CHECK (language ~ '^[a-z]{2,3}(_[A-Z]{2})?$'),
    CONSTRAINT ck_tenant_provider_templates_parameters CHECK (
        cardinality(parameter_names) <= 10 AND array_position(parameter_names, NULL) IS NULL),
    CONSTRAINT ck_tenant_provider_templates_status CHECK (approval_status IN (
        'UNVERIFIED', 'APPROVED', 'PENDING', 'REJECTED', 'PAUSED', 'DISABLED', 'DELETED', 'NOT_FOUND')),
    -- Evidence is complete or absent: a status without a check time, account and revision cannot exist.
    CONSTRAINT ck_tenant_provider_templates_evidence CHECK (
        (approval_status = 'UNVERIFIED' AND approval_checked_at IS NULL
            AND approval_checked_business_account_id IS NULL AND approval_checked_version IS NULL)
        OR (approval_status <> 'UNVERIFIED' AND approval_checked_at IS NOT NULL
            AND approval_checked_business_account_id ~ '^[0-9]{5,32}$' AND approval_checked_version IS NOT NULL)),
    CONSTRAINT ck_tenant_provider_templates_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_tenant_provider_templates_version CHECK (version >= 0)
);

REVOKE ALL ON TABLE dokene.tenant_provider_templates FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.tenant_provider_templates TO dokene_runtime;

ALTER TABLE dokene.tenant_provider_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenant_provider_templates FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_provider_templates_select_policy ON dokene.tenant_provider_templates
    FOR SELECT TO dokene_runtime USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_provider_templates_insert_policy ON dokene.tenant_provider_templates
    FOR INSERT TO dokene_runtime WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_provider_templates_update_policy ON dokene.tenant_provider_templates
    FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_provider_templates_delete_policy ON dokene.tenant_provider_templates
    FOR DELETE TO dokene_runtime USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_provider_templates_migration_policy ON dokene.tenant_provider_templates
    FOR ALL TO dokene_migration USING (true) WITH CHECK (true);
