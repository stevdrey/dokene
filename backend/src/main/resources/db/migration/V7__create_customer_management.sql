CREATE TABLE dokene.customers (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    notes VARCHAR(2000),
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    archived_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_customers_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_customers_tenant FOREIGN KEY (tenant_id) REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT ck_customers_name_not_blank CHECK (
        display_name ~ U&'[^[:space:]\001C-\001F\0085\00A0\1680\2000-\200A\2007\2028\2029\202F\205F\3000]'
    ),
    CONSTRAINT ck_customers_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_customers_archive_shape CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL) OR (status = 'ARCHIVED' AND archived_at IS NOT NULL)
    ),
    CONSTRAINT ck_customers_timestamps CHECK (updated_at >= created_at AND (archived_at IS NULL OR archived_at >= created_at))
);

CREATE TABLE dokene.customer_phone_contacts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    normalized_phone VARCHAR(16) NOT NULL,
    is_primary BOOLEAN NOT NULL,
    CONSTRAINT fk_customer_phones_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT uq_customer_phones_tenant_phone UNIQUE (tenant_id, normalized_phone),
    CONSTRAINT ck_customer_phones_e164 CHECK (normalized_phone ~ '^\+[1-9][0-9]{1,14}$')
);

CREATE UNIQUE INDEX uq_customer_phones_primary
    ON dokene.customer_phone_contacts(customer_id) WHERE is_primary;
CREATE INDEX customers_tenant_chronology ON dokene.customers(tenant_id, created_at DESC, id DESC);
CREATE INDEX customers_tenant_name ON dokene.customers(tenant_id, lower(display_name));
CREATE INDEX customer_phones_customer ON dokene.customer_phone_contacts(tenant_id, customer_id);

REVOKE ALL ON TABLE dokene.customers, dokene.customer_phone_contacts FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.customers, dokene.customer_phone_contacts TO dokene_runtime;
GRANT DELETE ON TABLE dokene.customer_phone_contacts TO dokene_runtime;

ALTER TABLE dokene.customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customers FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_phone_contacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_phone_contacts FORCE ROW LEVEL SECURITY;

CREATE POLICY customers_select ON dokene.customers FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customers_insert ON dokene.customers FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customers_update ON dokene.customers FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customers_migration ON dokene.customers FOR ALL TO dokene_migration USING (true) WITH CHECK (true);

CREATE POLICY customer_phones_select ON dokene.customer_phone_contacts FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customer_phones_insert ON dokene.customer_phone_contacts FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customer_phones_update ON dokene.customer_phone_contacts FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customer_phones_delete ON dokene.customer_phone_contacts FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customer_phones_migration ON dokene.customer_phone_contacts FOR ALL TO dokene_migration USING (true) WITH CHECK (true);

ALTER TABLE dokene.audit_events DROP CONSTRAINT ck_audit_shape;
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_shape CHECK (
    (event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
        AND target_type IS NULL AND target_id IS NULL
        AND previous_role IS NULL AND new_role IS NULL
        AND denial_reason IS NOT NULL AND denial_reason IN (
            'NO_TENANT_CONTEXT', 'INACTIVE_MEMBERSHIP', 'MISSING_ROLE', 'MISSING_PERMISSION',
            'MISSING_RESOURCE_TENANT', 'CROSS_TENANT_RESOURCE', 'INSUFFICIENT_PERMISSION', 'UNSPECIFIED')
        AND ((tenant_id IS NULL AND denial_reason = 'NO_TENANT_CONTEXT')
            OR (tenant_id IS NOT NULL AND denial_reason <> 'NO_TENANT_CONTEXT')))
    OR (event_type = 'MEMBERSHIP_ROLE_CHANGED' AND outcome = 'SUCCESS'
        AND tenant_id IS NOT NULL AND target_type = 'MEMBERSHIP' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL
        AND previous_role IN ('ADMIN', 'OPERATOR', 'VIEWER')
        AND new_role IN ('ADMIN', 'OPERATOR', 'VIEWER') AND previous_role <> new_role)
    OR (event_type IN ('CUSTOMER_CREATED', 'CUSTOMER_UPDATED', 'CUSTOMER_ARCHIVED') AND outcome = 'SUCCESS'
        AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
);
