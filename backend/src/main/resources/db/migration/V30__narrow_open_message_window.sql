-- ABOUTME: Narrows the one open message per customer index to the states before provider acceptance (issue 198,
-- ABOUTME: ADR 0023 §2 amended 2026-10-22). SENT and DELIVERED stay non terminal but no longer block a new message.

-- Exactly once rests on QUEUED and SENDING blocking the customer; a message the provider accepted only waits on
-- delivery reports, which may never arrive. Flyway runs this file in one transaction, so no window exists without
-- the index. No row can violate the narrower predicate, because it covers a subset of the rows the old one covered.
DROP INDEX dokene.one_open_message_per_customer;

CREATE UNIQUE INDEX one_open_message_per_customer
    ON dokene.outbound_messages(tenant_id, customer_id)
    WHERE status IN ('PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'SENDING');
