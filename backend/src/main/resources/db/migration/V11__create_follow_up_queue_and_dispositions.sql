ALTER TABLE dokene.customer_follow_up_policies
    ADD COLUMN last_dismissed_date DATE;

ALTER TABLE dokene.manual_follow_up_completions
    ADD COLUMN notes VARCHAR(500);

CREATE TABLE dokene.follow_up_dismissals (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    dismissed_on DATE NOT NULL,
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    actor_id UUID NOT NULL,
    membership_id UUID NOT NULL,
    notes VARCHAR(500),
    UNIQUE (tenant_id, idempotency_key),
    CONSTRAINT fk_follow_up_dismissal_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT
);

CREATE INDEX follow_up_dismissals_customer
    ON dokene.follow_up_dismissals(tenant_id, customer_id, dismissed_on DESC, id DESC);

REVOKE ALL ON TABLE dokene.follow_up_dismissals FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE dokene.follow_up_dismissals TO dokene_runtime;

ALTER TABLE dokene.follow_up_dismissals ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.follow_up_dismissals FORCE ROW LEVEL SECURITY;

CREATE POLICY follow_up_dismissals_select ON dokene.follow_up_dismissals FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY follow_up_dismissals_insert ON dokene.follow_up_dismissals FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY follow_up_dismissals_migration ON dokene.follow_up_dismissals FOR ALL TO dokene_migration
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
    OR (event_type IN ('CUSTOMER_FOLLOW_UP_POLICY_CHANGED', 'FOLLOW_UP_SNOOZED',
            'MANUAL_FOLLOW_UP_RECORDED', 'FOLLOW_UP_DISMISSED')
        AND outcome = 'SUCCESS' AND tenant_id IS NOT NULL AND target_type = 'CUSTOMER' AND target_id IS NOT NULL
        AND permission IS NULL AND denial_reason IS NULL AND previous_role IS NULL AND new_role IS NULL)
);
