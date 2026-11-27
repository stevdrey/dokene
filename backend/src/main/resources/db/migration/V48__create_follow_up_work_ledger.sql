-- ABOUTME: Issue 458 (Phase 4 A2, ADR 0037): the durable follow-up work ledger. One row per follow-up candidate, claimed
-- ABOUTME: by one instance at a time under a lease with a fencing identity. A claim is a record only: it sends nothing.

-- Expand only: a backend of the previous release never reads or writes the new table. The unique key below lets the
-- ledger reference a candidate together with the customer and due date it copies, so the copies cannot drift.
ALTER TABLE dokene.follow_up_candidates
    ADD CONSTRAINT uq_follow_up_candidates_work_identity UNIQUE (tenant_id, id, customer_id, due_date);

CREATE TABLE dokene.follow_up_work_items (
    candidate_id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    due_date DATE NOT NULL,
    state VARCHAR(16) NOT NULL,
    -- Claims made so far. The current claim's attempt number plus its claim token are its fencing identity.
    attempts INTEGER NOT NULL DEFAULT 0,
    -- When a PENDING or RETRY_DUE item may next be claimed.
    available_at TIMESTAMPTZ NOT NULL,
    claim_token UUID,
    claimed_by VARCHAR(64),
    claimed_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    last_failure VARCHAR(32),
    -- Set when the item was resolved together with another candidate of the same customer (ADR 0036, ADR 0037).
    collapsed_into UUID,
    enqueued_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_follow_up_work_items_tenant_candidate UNIQUE (tenant_id, candidate_id),
    CONSTRAINT fk_follow_up_work_items_candidate FOREIGN KEY (tenant_id, candidate_id, customer_id, due_date)
        REFERENCES dokene.follow_up_candidates (tenant_id, id, customer_id, due_date) ON DELETE RESTRICT,
    CONSTRAINT fk_follow_up_work_items_collapsed_into FOREIGN KEY (tenant_id, collapsed_into)
        REFERENCES dokene.follow_up_candidates (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_follow_up_work_items_state CHECK (state IN (
        'PENDING', 'CLAIMED', 'COMPLETED', 'RETRY_DUE', 'EXHAUSTED')),
    CONSTRAINT ck_follow_up_work_items_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_follow_up_work_items_claimed_by CHECK (claimed_by IS NULL OR claimed_by ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT ck_follow_up_work_items_claim CHECK (state <> 'CLAIMED' OR (
        attempts >= 1 AND claim_token IS NOT NULL AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL
        AND lease_expires_at IS NOT NULL AND lease_expires_at > claimed_at)),
    CONSTRAINT ck_follow_up_work_items_finished CHECK (
        (state IN ('COMPLETED', 'EXHAUSTED')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_follow_up_work_items_failure CHECK (
        (state NOT IN ('RETRY_DUE', 'EXHAUSTED') OR last_failure IS NOT NULL)
        AND (last_failure IS NULL OR last_failure IN (
            'PROCESSING_ERROR', 'DEPENDENCY_UNAVAILABLE', 'LEASE_EXPIRED', 'OUTCOME_UNKNOWN'))),
    CONSTRAINT ck_follow_up_work_items_collapsed CHECK (collapsed_into IS NULL OR (
        state = 'COMPLETED' AND attempts = 0 AND collapsed_into <> candidate_id))
    -- No ordering check across instants: instants come from the clocks of different instances.
);

-- At most one open item per customer: a customer's open candidates are handled together, never as separate
-- follow-ups, and an item that needs recovery holds back newer work for that customer until it is resolved.
CREATE UNIQUE INDEX uq_follow_up_work_items_open_customer ON dokene.follow_up_work_items (tenant_id, customer_id)
    WHERE state IN ('PENDING', 'CLAIMED', 'RETRY_DUE', 'EXHAUSTED');
CREATE INDEX ix_follow_up_work_items_available ON dokene.follow_up_work_items (tenant_id, available_at)
    WHERE state IN ('PENDING', 'RETRY_DUE');
CREATE INDEX ix_follow_up_work_items_lease ON dokene.follow_up_work_items (tenant_id, lease_expires_at)
    WHERE state = 'CLAIMED';

-- The runtime enqueues, claims and records outcomes of its own tenant's items; it can never remove one.
REVOKE ALL ON TABLE dokene.follow_up_work_items FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.follow_up_work_items TO dokene_runtime;

ALTER TABLE dokene.follow_up_work_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.follow_up_work_items FORCE ROW LEVEL SECURITY;

CREATE POLICY follow_up_work_items_select_policy ON dokene.follow_up_work_items FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY follow_up_work_items_insert_policy ON dokene.follow_up_work_items FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY follow_up_work_items_update_policy ON dokene.follow_up_work_items FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY follow_up_work_items_migration_policy ON dokene.follow_up_work_items FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);
