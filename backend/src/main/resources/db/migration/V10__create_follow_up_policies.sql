CREATE TABLE dokene.tenant_follow_up_policies (
    tenant_id UUID PRIMARY KEY REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    cadence_days INTEGER NOT NULL DEFAULT 30 CHECK (cadence_days BETWEEN 1 AND 3650),
    time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC' CHECK (length(btrim(time_zone)) > 0),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);

CREATE TABLE dokene.customer_follow_up_policies (
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    cadence_days INTEGER CHECK (cadence_days BETWEEN 1 AND 3650),
    explicit_next_date DATE,
    snoozed_until DATE,
    last_manual_follow_up_date DATE,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (tenant_id, customer_id),
    CONSTRAINT fk_customer_follow_up_policy FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT
);

INSERT INTO dokene.tenant_follow_up_policies (tenant_id)
SELECT id FROM dokene.tenants;

INSERT INTO dokene.customer_follow_up_policies (tenant_id, customer_id)
SELECT tenant_id, id FROM dokene.customers;

CREATE FUNCTION dokene.create_default_follow_up_policy()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
BEGIN
    INSERT INTO dokene.tenant_follow_up_policies (tenant_id) VALUES (NEW.id);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION dokene.create_default_follow_up_policy() FROM PUBLIC;
CREATE TRIGGER tenants_default_follow_up_policy
    AFTER INSERT ON dokene.tenants FOR EACH ROW
    EXECUTE FUNCTION dokene.create_default_follow_up_policy();

CREATE FUNCTION dokene.create_default_customer_follow_up_policy()
RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, dokene AS $$
BEGIN
    INSERT INTO dokene.customer_follow_up_policies (tenant_id, customer_id) VALUES (NEW.tenant_id, NEW.id);
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION dokene.create_default_customer_follow_up_policy() FROM PUBLIC;
CREATE TRIGGER customers_default_follow_up_policy
    AFTER INSERT ON dokene.customers FOR EACH ROW
    EXECUTE FUNCTION dokene.create_default_customer_follow_up_policy();

CREATE TABLE dokene.manual_follow_up_completions (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    completed_on DATE NOT NULL,
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_manual_follow_up_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT
);

REVOKE ALL ON TABLE dokene.tenant_follow_up_policies, dokene.customer_follow_up_policies,
    dokene.manual_follow_up_completions FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.tenant_follow_up_policies,
    dokene.customer_follow_up_policies TO dokene_runtime;
GRANT SELECT, INSERT ON TABLE dokene.manual_follow_up_completions TO dokene_runtime;

ALTER TABLE dokene.tenant_follow_up_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenant_follow_up_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_follow_up_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_follow_up_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.manual_follow_up_completions ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.manual_follow_up_completions FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_follow_up_runtime ON dokene.tenant_follow_up_policies TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customer_follow_up_runtime ON dokene.customer_follow_up_policies TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY manual_follow_up_select ON dokene.manual_follow_up_completions FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY manual_follow_up_insert ON dokene.manual_follow_up_completions FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_follow_up_migration ON dokene.tenant_follow_up_policies TO dokene_migration
    USING (true) WITH CHECK (true);
CREATE POLICY customer_follow_up_migration ON dokene.customer_follow_up_policies TO dokene_migration
    USING (true) WITH CHECK (true);
CREATE POLICY manual_follow_up_migration ON dokene.manual_follow_up_completions TO dokene_migration
    USING (true) WITH CHECK (true);

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
    OR (event_type = 'TENANT_FOLLOW_UP_POLICY_CHANGED'
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'TENANT' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
    OR (event_type IN ('CUSTOMER_FOLLOW_UP_POLICY_CHANGED', 'FOLLOW_UP_SNOOZED', 'MANUAL_FOLLOW_UP_RECORDED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
);
