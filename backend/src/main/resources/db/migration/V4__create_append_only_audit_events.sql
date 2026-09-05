CREATE TABLE dokene.audit_events (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    tenant_id UUID,
    actor_id UUID,
    membership_id UUID,
    event_type VARCHAR(40) NOT NULL,
    target_type VARCHAR(24),
    target_id UUID,
    outcome VARCHAR(8) NOT NULL,
    correlation_id UUID NOT NULL,
    permission VARCHAR(40),
    denial_reason VARCHAR(32),
    previous_role VARCHAR(16),
    new_role VARCHAR(16),
    CONSTRAINT ck_audit_attribution CHECK (
        (tenant_id IS NOT NULL AND actor_id IS NOT NULL AND membership_id IS NOT NULL)
        OR (tenant_id IS NULL AND actor_id IS NULL AND membership_id IS NULL)
    ),
    CONSTRAINT ck_audit_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE')),
    CONSTRAINT ck_audit_shape CHECK (
        (event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
            AND target_type IS NULL AND target_id IS NULL
            AND previous_role IS NULL AND new_role IS NULL
            AND denial_reason IS NOT NULL AND denial_reason IN (
                'NO_TENANT_CONTEXT', 'INACTIVE_MEMBERSHIP', 'MISSING_ROLE', 'MISSING_PERMISSION',
                'MISSING_RESOURCE_TENANT', 'CROSS_TENANT_RESOURCE', 'INSUFFICIENT_PERMISSION', 'UNSPECIFIED')
            AND ((tenant_id IS NULL AND denial_reason = 'NO_TENANT_CONTEXT')
                OR (tenant_id IS NOT NULL AND denial_reason <> 'NO_TENANT_CONTEXT')))
        OR (event_type = 'MEMBERSHIP_ROLE_CHANGED' AND outcome = 'SUCCESS'
            AND tenant_id IS NOT NULL AND target_type IS NOT NULL AND target_type = 'MEMBERSHIP'
            AND target_id IS NOT NULL AND permission IS NULL AND denial_reason IS NULL
            AND previous_role IS NOT NULL AND previous_role IN ('ADMIN', 'OPERATOR', 'VIEWER')
            AND new_role IS NOT NULL AND new_role IN ('ADMIN', 'OPERATOR', 'VIEWER')
            AND previous_role <> new_role)
    ),
    CONSTRAINT ck_audit_permission CHECK (permission IS NULL OR permission IN (
        'TENANT_READ', 'TENANT_UPDATE', 'TENANT_ARCHIVE',
        'MEMBERSHIP_READ', 'MEMBERSHIP_INVITE', 'MEMBERSHIP_ROLE_UPDATE', 'MEMBERSHIP_REVOKE',
        'CUSTOMER_READ', 'CUSTOMER_WRITE', 'CUSTOMER_DELETE',
        'FOLLOWUP_READ', 'FOLLOWUP_WRITE', 'FOLLOWUP_EVALUATE',
        'TEMPLATE_READ', 'TEMPLATE_WRITE',
        'MESSAGE_READ', 'MESSAGE_DRAFT', 'MESSAGE_APPROVE', 'MESSAGE_SEND',
        'INTEGRATION_READ', 'INTEGRATION_MANAGE', 'AUDIT_READ', 'DATA_EXPORT'
    ))
);

-- Historical references intentionally have no cascading foreign keys.
CREATE INDEX audit_events_tenant_chronology ON dokene.audit_events (tenant_id, occurred_at DESC, id DESC);

REVOKE ALL ON TABLE dokene.audit_events FROM PUBLIC, dokene_runtime;
GRANT SELECT, INSERT ON TABLE dokene.audit_events TO dokene_runtime;
ALTER TABLE dokene.audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_events_select ON dokene.audit_events FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY audit_events_insert ON dokene.audit_events FOR INSERT TO dokene_runtime
    WITH CHECK (
        tenant_id = dokene.current_verified_tenant_id()
        OR (tenant_id IS NULL AND actor_id IS NULL AND membership_id IS NULL
            AND event_type = 'AUTHORIZATION_DENIED' AND denial_reason = 'NO_TENANT_CONTEXT'
            AND dokene.current_verified_tenant_id() IS NULL)
    );
-- No runtime UPDATE/DELETE policy; no TRUNCATE, ownership, or RLS-bypass privileges.
CREATE POLICY audit_events_migration ON dokene.audit_events FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);
