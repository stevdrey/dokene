ALTER TABLE dokene.customers
    ADD COLUMN contact_policy_version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_customers_contact_policy_version CHECK (contact_policy_version >= 0);

ALTER TABLE dokene.customer_phone_contacts
    ADD CONSTRAINT uq_customer_phones_tenant_customer_id UNIQUE (tenant_id, customer_id, id);

CREATE TABLE dokene.customer_contact_consents (
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    phone_contact_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source VARCHAR(32) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, customer_id, phone_contact_id, channel),
    CONSTRAINT fk_contact_consents_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_contact_consents_phone FOREIGN KEY (tenant_id, customer_id, phone_contact_id)
        REFERENCES dokene.customer_phone_contacts(tenant_id, customer_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_contact_consents_channel CHECK (channel = 'WHATSAPP'),
    CONSTRAINT ck_contact_consents_status CHECK (status IN ('GRANTED', 'REVOKED')),
    CONSTRAINT ck_contact_consents_source CHECK (source IN ('CUSTOMER_VERBAL', 'CUSTOMER_WRITTEN', 'OPERATOR_CORRECTION'))
);

CREATE TABLE dokene.customer_consent_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    phone_contact_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    source VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    policy_version BIGINT NOT NULL,
    CONSTRAINT fk_consent_history_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_consent_history_channel CHECK (channel = 'WHATSAPP'),
    CONSTRAINT ck_consent_history_status CHECK (status IN ('GRANTED', 'REVOKED')),
    CONSTRAINT ck_consent_history_source CHECK (source IN ('CUSTOMER_VERBAL', 'CUSTOMER_WRITTEN', 'OPERATOR_CORRECTION')),
    CONSTRAINT ck_consent_history_version CHECK (policy_version > 0)
);

CREATE TABLE dokene.customer_do_not_contact (
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL,
    source VARCHAR(32) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    PRIMARY KEY (tenant_id, customer_id),
    CONSTRAINT fk_do_not_contact_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_do_not_contact_source CHECK (source IN ('CUSTOMER_VERBAL', 'CUSTOMER_WRITTEN', 'OPERATOR_CORRECTION'))
);

CREATE TABLE dokene.customer_do_not_contact_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    enabled BOOLEAN NOT NULL,
    source VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    policy_version BIGINT NOT NULL,
    CONSTRAINT fk_do_not_contact_history_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_do_not_contact_history_source CHECK (source IN ('CUSTOMER_VERBAL', 'CUSTOMER_WRITTEN', 'OPERATOR_CORRECTION')),
    CONSTRAINT ck_do_not_contact_history_version CHECK (policy_version > 0)
);

CREATE INDEX consent_history_customer_chronology
    ON dokene.customer_consent_history (tenant_id, customer_id, occurred_at DESC, id DESC);
CREATE INDEX do_not_contact_history_customer_chronology
    ON dokene.customer_do_not_contact_history (tenant_id, customer_id, occurred_at DESC, id DESC);

REVOKE ALL ON TABLE dokene.customer_contact_consents, dokene.customer_consent_history,
    dokene.customer_do_not_contact, dokene.customer_do_not_contact_history FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.customer_contact_consents, dokene.customer_do_not_contact TO dokene_runtime;
GRANT SELECT, INSERT ON TABLE dokene.customer_consent_history, dokene.customer_do_not_contact_history TO dokene_runtime;

ALTER TABLE dokene.customer_contact_consents ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_contact_consents FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_consent_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_consent_history FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_do_not_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_do_not_contact FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_do_not_contact_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_do_not_contact_history FORCE ROW LEVEL SECURITY;

CREATE POLICY contact_consents_runtime ON dokene.customer_contact_consents TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY consent_history_select ON dokene.customer_consent_history FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY consent_history_insert ON dokene.customer_consent_history FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY do_not_contact_runtime ON dokene.customer_do_not_contact TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY do_not_contact_history_select ON dokene.customer_do_not_contact_history FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY do_not_contact_history_insert ON dokene.customer_do_not_contact_history FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());

CREATE POLICY contact_consents_migration ON dokene.customer_contact_consents FOR ALL TO dokene_migration USING (true) WITH CHECK (true);
CREATE POLICY consent_history_migration ON dokene.customer_consent_history FOR ALL TO dokene_migration USING (true) WITH CHECK (true);
CREATE POLICY do_not_contact_migration ON dokene.customer_do_not_contact FOR ALL TO dokene_migration USING (true) WITH CHECK (true);
CREATE POLICY do_not_contact_history_migration ON dokene.customer_do_not_contact_history FOR ALL TO dokene_migration USING (true) WITH CHECK (true);

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
    OR (event_type IN ('CUSTOMER_CREATED', 'CUSTOMER_UPDATED', 'CUSTOMER_ARCHIVED',
            'CUSTOMER_CONSENT_CHANGED', 'CUSTOMER_DO_NOT_CONTACT_CHANGED') AND outcome = 'SUCCESS'
        AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
);
