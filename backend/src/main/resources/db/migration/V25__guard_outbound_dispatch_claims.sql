-- ABOUTME: Manual dispatch (issue 138): at most one STARTED attempt per message is the durable claim, and the
-- ABOUTME: index backing the per-tenant send rate bound. Additive only; no existing row can violate the new index.

-- The STARTED attempt row is the dispatch intent: it commits with T6 and T7 before the provider call and names the
-- authorizing member. A second concurrent claim for the same message fails here even if the version check missed it.
-- V18 already allows no attempt to start while another is STARTED (T7 requires QUEUED), so existing data conforms.
CREATE UNIQUE INDEX one_started_attempt_per_message
    ON dokene.outbound_send_attempts(tenant_id, message_id)
    WHERE outcome = 'STARTED';

-- Send policy abuse bound (ADR 0024 §5): attempts started per tenant in a sliding window.
CREATE INDEX outbound_send_attempts_tenant_started
    ON dokene.outbound_send_attempts(tenant_id, started_at);
