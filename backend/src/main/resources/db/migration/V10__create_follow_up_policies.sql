CREATE TABLE dokene.tenant_follow_up_policies (
    tenant_id UUID PRIMARY KEY REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    cadence_days INTEGER NOT NULL DEFAULT 30 CHECK (cadence_days BETWEEN 1 AND 3650),
    time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC' CHECK (length(btrim(time_zone)) > 0)
);

CREATE TABLE dokene.customer_follow_up_policies (
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    cadence_days INTEGER CHECK (cadence_days BETWEEN 1 AND 3650),
    explicit_next_date DATE,
    snoozed_until DATE,
    last_manual_follow_up_date DATE,
    PRIMARY KEY (tenant_id, customer_id),
    CONSTRAINT fk_customer_follow_up_policy FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT
);

INSERT INTO dokene.tenant_follow_up_policies (tenant_id)
SELECT id FROM dokene.tenants;

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

REVOKE ALL ON TABLE dokene.tenant_follow_up_policies, dokene.customer_follow_up_policies FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.tenant_follow_up_policies,
    dokene.customer_follow_up_policies TO dokene_runtime;

ALTER TABLE dokene.tenant_follow_up_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.tenant_follow_up_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_follow_up_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.customer_follow_up_policies FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_follow_up_runtime ON dokene.tenant_follow_up_policies TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY customer_follow_up_runtime ON dokene.customer_follow_up_policies TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY tenant_follow_up_migration ON dokene.tenant_follow_up_policies TO dokene_migration
    USING (true) WITH CHECK (true);
CREATE POLICY customer_follow_up_migration ON dokene.customer_follow_up_policies TO dokene_migration
    USING (true) WITH CHECK (true);
