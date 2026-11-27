-- ABOUTME: Issue 460 (Phase 4 A1, ADR 0036): recurring follow-up candidates, one per tenant, customer and due cycle,
-- ABOUTME: generated from the due queue derivation. A candidate is a record only: it approves, schedules or sends nothing.

-- Expand only: a backend of the previous release never reads or writes the new table.
CREATE TABLE dokene.follow_up_candidates (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    -- The due cycle: the cycle's due date in the tenant's calendar (ADR 0013 anchor precedence, snooze excluded).
    due_date DATE NOT NULL,
    timing_source VARCHAR(32) NOT NULL,
    effective_cadence_days INTEGER NOT NULL,
    -- The customer policy version and tenant-local date the generating scan evaluated.
    policy_version BIGINT NOT NULL,
    generated_on DATE NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_follow_up_candidates_cycle UNIQUE (tenant_id, customer_id, due_date),
    CONSTRAINT uq_follow_up_candidates_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_follow_up_candidates_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_follow_up_candidates_timing_source CHECK (timing_source IN (
        'EXPLICIT_DATE', 'LAST_MANUAL_FOLLOW_UP', 'LAST_DISMISSAL', 'LAST_OUTBOUND_MESSAGE', 'LAST_PURCHASE')),
    CONSTRAINT ck_follow_up_candidates_cadence CHECK (effective_cadence_days >= 1),
    CONSTRAINT ck_follow_up_candidates_policy_version CHECK (policy_version >= 0),
    CONSTRAINT ck_follow_up_candidates_due CHECK (due_date <= generated_on)
);

-- Scans and consumers read a tenant's candidates in due order.
CREATE INDEX ix_follow_up_candidates_tenant_due ON dokene.follow_up_candidates (tenant_id, due_date, customer_id);

-- The runtime only reads and inserts its own tenant's candidates. Candidate state (claims, completion) belongs to the
-- work ledger (issue 458), which adds its own grants; generation can neither rewrite nor remove a candidate.
REVOKE ALL ON TABLE dokene.follow_up_candidates FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE dokene.follow_up_candidates TO dokene_runtime;

ALTER TABLE dokene.follow_up_candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.follow_up_candidates FORCE ROW LEVEL SECURITY;

CREATE POLICY follow_up_candidates_select_policy ON dokene.follow_up_candidates FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY follow_up_candidates_insert_policy ON dokene.follow_up_candidates FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY follow_up_candidates_migration_policy ON dokene.follow_up_candidates FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);
