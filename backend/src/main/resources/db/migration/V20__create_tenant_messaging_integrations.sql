-- ABOUTME: Creates the tenant WhatsApp integration record (ADR 0023 §4.7, issue 134): non-secret Meta identifiers,
-- ABOUTME: the enabled flag and a secret reference, under forced RLS with a globally unique phone number mapping.

CREATE TABLE dokene.tenant_messaging_integrations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    provider VARCHAR(24) NOT NULL,
    phone_number_id VARCHAR(32) NOT NULL,
    business_account_id VARCHAR(32) NOT NULL,
    display_phone_number VARCHAR(16),
    enabled BOOLEAN NOT NULL,
    secret_ref VARCHAR(128) NOT NULL,
    credential_revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_tenant_messaging_integrations_tenant_id UNIQUE (tenant_id, id),
    -- One integration per tenant and provider; the service upserts it.
    CONSTRAINT uq_tenant_messaging_integrations_tenant_provider UNIQUE (tenant_id, provider),
    -- Webhook tenant resolution is by phone number id, so a mapping must belong to exactly one tenant.
    CONSTRAINT uq_tenant_messaging_integrations_phone_number UNIQUE (provider, phone_number_id),
    CONSTRAINT fk_tenant_messaging_integrations_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT ck_tenant_messaging_integrations_provider CHECK (provider IN ('META_WHATSAPP')),
    CONSTRAINT ck_tenant_messaging_integrations_phone_number_id CHECK (phone_number_id ~ '^[0-9]{5,32}$'),
    CONSTRAINT ck_tenant_messaging_integrations_business_account_id CHECK (business_account_id ~ '^[0-9]{5,32}$'),
    CONSTRAINT ck_tenant_messaging_integrations_display_phone CHECK (
        display_phone_number IS NULL OR display_phone_number ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT ck_tenant_messaging_integrations_secret_ref CHECK (secret_ref ~ '^[A-Za-z0-9_-]{1,128}$'),
    CONSTRAINT ck_tenant_messaging_integrations_credential_revision CHECK (credential_revision >= 1),
    CONSTRAINT ck_tenant_messaging_integrations_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_tenant_messaging_integrations_version CHECK (version >= 0)
);

REVOKE ALL ON TABLE dokene.tenant_messaging_integrations FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.tenant_messaging_integrations TO dokene_runtime;

ALTER TABLE dokene.tenant_messaging_integrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenant_messaging_integrations FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_messaging_integrations_select_policy ON dokene.tenant_messaging_integrations
    FOR SELECT TO dokene_runtime USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_messaging_integrations_insert_policy ON dokene.tenant_messaging_integrations
    FOR INSERT TO dokene_runtime WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_messaging_integrations_update_policy ON dokene.tenant_messaging_integrations
    FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_messaging_integrations_delete_policy ON dokene.tenant_messaging_integrations
    FOR DELETE TO dokene_runtime USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_messaging_integrations_migration_policy ON dokene.tenant_messaging_integrations
    FOR ALL TO dokene_migration USING (true) WITH CHECK (true);
