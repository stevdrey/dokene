CREATE TABLE dokene.purchases (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    purchased_at TIMESTAMPTZ NOT NULL,
    description VARCHAR(500) NOT NULL,
    status VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    voided_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    idempotency_key VARCHAR(128) NOT NULL,
    submission_fingerprint CHAR(64) NOT NULL,
    CONSTRAINT uq_purchases_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_purchases_tenant_idempotency UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_purchases_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_purchases_description CHECK (length(btrim(description)) > 0),
    CONSTRAINT ck_purchases_status CHECK (status IN ('VALID', 'VOID')),
    CONSTRAINT ck_purchases_void_shape CHECK (
        (status = 'VALID' AND voided_at IS NULL) OR (status = 'VOID' AND voided_at IS NOT NULL)
    ),
    CONSTRAINT ck_purchases_timestamps CHECK (updated_at >= created_at AND (voided_at IS NULL OR voided_at >= created_at)),
    CONSTRAINT ck_purchases_idempotency CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT ck_purchases_fingerprint CHECK (submission_fingerprint ~ '^[0-9a-f]{64}$')
);

CREATE TABLE dokene.purchase_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    purchase_id UUID NOT NULL,
    event_type VARCHAR(16) NOT NULL,
    purchased_at TIMESTAMPTZ NOT NULL,
    description VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    purchase_version BIGINT NOT NULL,
    CONSTRAINT fk_purchase_history_purchase FOREIGN KEY (tenant_id, purchase_id)
        REFERENCES dokene.purchases(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_purchase_history_type CHECK (event_type IN ('RECORDED', 'CORRECTED', 'VOIDED')),
    CONSTRAINT ck_purchase_history_description CHECK (length(btrim(description)) > 0),
    CONSTRAINT ck_purchase_history_version CHECK (purchase_version >= 0)
);

CREATE INDEX purchases_customer_chronology
    ON dokene.purchases(tenant_id, customer_id, purchased_at DESC, id DESC);
CREATE INDEX purchases_customer_last_valid
    ON dokene.purchases(tenant_id, customer_id, purchased_at DESC, id DESC) WHERE status = 'VALID';
CREATE INDEX purchase_history_chronology
    ON dokene.purchase_history(tenant_id, purchase_id, occurred_at DESC, id DESC);

CREATE FUNCTION dokene.prevent_purchase_identity_change()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, dokene AS $$
BEGIN
    IF OLD.status = 'VOID' THEN
        RAISE EXCEPTION 'Voided purchases are immutable' USING ERRCODE = '23000';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
            OR NEW.idempotency_key IS DISTINCT FROM OLD.idempotency_key
            OR NEW.submission_fingerprint IS DISTINCT FROM OLD.submission_fingerprint
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Purchase identity is immutable' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER purchases_identity_immutable BEFORE UPDATE ON dokene.purchases
    FOR EACH ROW EXECUTE FUNCTION dokene.prevent_purchase_identity_change();

REVOKE ALL ON TABLE dokene.purchases, dokene.purchase_history FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.purchases TO dokene_runtime;
GRANT SELECT, INSERT ON TABLE dokene.purchase_history TO dokene_runtime;
ALTER TABLE dokene.purchases ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.purchases FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.purchase_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.purchase_history FORCE ROW LEVEL SECURITY;

CREATE POLICY purchases_select ON dokene.purchases FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY purchases_insert ON dokene.purchases FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY purchases_update ON dokene.purchases FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY purchases_migration ON dokene.purchases FOR ALL TO dokene_migration USING (true) WITH CHECK (true);
CREATE POLICY purchase_history_select ON dokene.purchase_history FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY purchase_history_insert ON dokene.purchase_history FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY purchase_history_migration ON dokene.purchase_history FOR ALL TO dokene_migration USING (true) WITH CHECK (true);

ALTER TABLE dokene.audit_events DROP CONSTRAINT ck_audit_shape;
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_shape CHECK (
    (event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
        AND target_type IS NULL AND target_id IS NULL AND previous_role IS NULL AND new_role IS NULL
        AND denial_reason IS NOT NULL AND denial_reason IN (
            'NO_TENANT_CONTEXT', 'INACTIVE_MEMBERSHIP', 'MISSING_ROLE', 'MISSING_PERMISSION',
            'MISSING_RESOURCE_TENANT', 'CROSS_TENANT_RESOURCE', 'INSUFFICIENT_PERMISSION', 'UNSPECIFIED')
        AND ((tenant_id IS NULL AND denial_reason = 'NO_TENANT_CONTEXT')
            OR (tenant_id IS NOT NULL AND denial_reason <> 'NO_TENANT_CONTEXT')))
    OR (event_type = 'MEMBERSHIP_ROLE_CHANGED' AND outcome = 'SUCCESS'
        AND tenant_id IS NOT NULL AND target_type = 'MEMBERSHIP' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL
        AND previous_role IN ('ADMIN', 'OPERATOR', 'VIEWER') AND new_role IN ('ADMIN', 'OPERATOR', 'VIEWER')
        AND previous_role <> new_role)
    OR (event_type IN ('CUSTOMER_CREATED', 'CUSTOMER_UPDATED', 'CUSTOMER_ARCHIVED',
            'CUSTOMER_CONSENT_CHANGED', 'CUSTOMER_DO_NOT_CONTACT_CHANGED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type IN ('PURCHASE_RECORDED', 'PURCHASE_CORRECTED', 'PURCHASE_VOIDED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'PURCHASE' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
);

ALTER TABLE dokene.audit_events DROP CONSTRAINT ck_audit_permission;
ALTER TABLE dokene.audit_events ADD CONSTRAINT ck_audit_permission CHECK (permission IS NULL OR permission IN (
    'TENANT_READ', 'TENANT_UPDATE', 'TENANT_ARCHIVE',
    'MEMBERSHIP_READ', 'MEMBERSHIP_INVITE', 'MEMBERSHIP_ROLE_UPDATE', 'MEMBERSHIP_REVOKE',
    'CUSTOMER_READ', 'CUSTOMER_WRITE', 'CUSTOMER_DELETE', 'PURCHASE_READ', 'PURCHASE_WRITE',
    'FOLLOWUP_READ', 'FOLLOWUP_WRITE', 'FOLLOWUP_EVALUATE', 'TEMPLATE_READ', 'TEMPLATE_WRITE',
    'MESSAGE_READ', 'MESSAGE_DRAFT', 'MESSAGE_APPROVE', 'MESSAGE_SEND',
    'INTEGRATION_READ', 'INTEGRATION_MANAGE', 'AUDIT_READ', 'DATA_EXPORT'
));
