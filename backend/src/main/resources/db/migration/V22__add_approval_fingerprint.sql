-- ABOUTME: Stores the approval fingerprint on each APPROVE decision (ADR 0024 §2, issue 136). Send recomputes it and
-- ABOUTME: refuses a mismatch; the value is never returned to the browser, written to audit or logged.

ALTER TABLE dokene.outbound_message_approvals
    ADD COLUMN approval_fingerprint CHAR(64);

-- An approval binds exactly one fingerprint and a rejection binds none. NOT VALID keeps any APPROVE row written before
-- this column existed readable; such a row has no fingerprint, so send policy step 5b can never match it and the
-- operator must cancel and resubmit. Every row written from now on is checked.
ALTER TABLE dokene.outbound_message_approvals
    ADD CONSTRAINT ck_outbound_message_approvals_fingerprint CHECK (
        (decision = 'APPROVE') = (approval_fingerprint IS NOT NULL)
        AND (approval_fingerprint IS NULL OR approval_fingerprint ~ '^[0-9a-f]{64}$')
    ) NOT VALID;
