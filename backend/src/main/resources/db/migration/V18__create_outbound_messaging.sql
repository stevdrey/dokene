-- ABOUTME: Creates the Phase 3 outbound messaging tables (ADR 0023 §4.7, spec 0001): messages, approvals,
-- ABOUTME: cancellations, send attempts, the append only event log and idempotency keys, all under forced RLS.

-- A sent message counts as a completed follow up (ADR 0023 §4.6).
ALTER TABLE dokene.customer_follow_up_policies
    ADD COLUMN last_outbound_message_date DATE;

CREATE TABLE dokene.outbound_messages (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    customer_id UUID NOT NULL,
    contact_id UUID NOT NULL,
    recipient_phone VARCHAR(20) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    outcome_unknown BOOLEAN NOT NULL DEFAULT false,
    action VARCHAR(40) NOT NULL,
    template_intent VARCHAR(40) NOT NULL,
    locale VARCHAR(16) NOT NULL,
    origin VARCHAR(16) NOT NULL,
    body VARCHAR(1000) NOT NULL,
    source_policy_version BIGINT NOT NULL,
    send_key UUID NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    provider_message_id VARCHAR(128),
    failure_category VARCHAR(32),
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    created_by_membership_id UUID NOT NULL,
    created_by_actor_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_outbound_messages_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_outbound_messages_send_key UNIQUE (send_key),
    CONSTRAINT fk_outbound_messages_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbound_messages_customer FOREIGN KEY (tenant_id, customer_id)
        REFERENCES dokene.customers(tenant_id, id) ON DELETE RESTRICT,
    -- contact_id is a snapshot of the contact chosen at submit, not a live reference: no foreign key, so the
    -- customer module can still delete a contact row when an operator corrects a phone (spec 0001, AC-13).
    -- Ownership is proven by the service under the customer lock at submit, approve and send.
    CONSTRAINT ck_outbound_messages_recipient_e164 CHECK (recipient_phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT ck_outbound_messages_channel CHECK (channel IN ('WHATSAPP')),
    CONSTRAINT ck_outbound_messages_status CHECK (status IN (
        'PENDING_APPROVAL', 'APPROVED', 'QUEUED', 'SENDING', 'SENT', 'DELIVERED', 'READ',
        'REJECTED', 'CANCELLED', 'FAILED')),
    CONSTRAINT ck_outbound_messages_outcome_unknown CHECK (NOT outcome_unknown OR status = 'SENDING'),
    CONSTRAINT ck_outbound_messages_action CHECK (action IN (
        'REPEAT_PURCHASE_FOLLOW_UP', 'GENERAL_CHECK_IN', 'RELATED_PRODUCT_OFFER',
        'DORMANT_REENGAGEMENT', 'SEASONAL_GREETING')),
    CONSTRAINT ck_outbound_messages_template_intent CHECK (template_intent IN (
        'GENERAL_FOLLOW_UP', 'REPEAT_PURCHASE', 'RELATED_PRODUCT', 'SEASONAL_EVENT', 'DORMANT_CUSTOMER')),
    CONSTRAINT ck_outbound_messages_locale CHECK (locale ~ '^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$'),
    CONSTRAINT ck_outbound_messages_origin CHECK (origin IN ('AI_DRAFT', 'MANUAL')),
    CONSTRAINT ck_outbound_messages_body_not_blank CHECK (length(btrim(body)) > 0),
    CONSTRAINT ck_outbound_messages_source_policy_version CHECK (source_policy_version >= 0),
    CONSTRAINT ck_outbound_messages_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbound_messages_failure_category CHECK (failure_category IS NULL OR (
        status = 'FAILED' AND failure_category IN (
            'INVALID_RECIPIENT', 'TEMPLATE_REJECTED', 'RATE_LIMITED', 'PROVIDER_UNAVAILABLE',
            'AUTHENTICATION', 'POLICY_VIOLATION', 'UNKNOWN'))),
    CONSTRAINT ck_outbound_messages_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_outbound_messages_version CHECK (version >= 0)
);

CREATE UNIQUE INDEX one_open_message_per_customer
    ON dokene.outbound_messages(tenant_id, customer_id)
    WHERE status NOT IN ('READ', 'REJECTED', 'CANCELLED', 'FAILED');
CREATE UNIQUE INDEX uq_outbound_messages_provider_message
    ON dokene.outbound_messages(tenant_id, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
CREATE INDEX outbound_messages_customer_sent
    ON dokene.outbound_messages(tenant_id, customer_id, sent_at);
CREATE INDEX outbound_messages_status_chronology
    ON dokene.outbound_messages(tenant_id, status, created_at DESC, id DESC);
CREATE INDEX outbound_messages_tenant_chronology
    ON dokene.outbound_messages(tenant_id, created_at DESC, id DESC);

-- Approval is bound to exactly the text and recipient that were submitted (ADR 0023 §2).
CREATE FUNCTION dokene.prevent_outbound_message_identity_change()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, dokene AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
            OR NEW.customer_id IS DISTINCT FROM OLD.customer_id
            OR NEW.contact_id IS DISTINCT FROM OLD.contact_id
            OR NEW.recipient_phone IS DISTINCT FROM OLD.recipient_phone
            OR NEW.channel IS DISTINCT FROM OLD.channel
            OR NEW.body IS DISTINCT FROM OLD.body
            OR NEW.action IS DISTINCT FROM OLD.action
            OR NEW.template_intent IS DISTINCT FROM OLD.template_intent
            OR NEW.locale IS DISTINCT FROM OLD.locale
            OR NEW.origin IS DISTINCT FROM OLD.origin
            OR NEW.source_policy_version IS DISTINCT FROM OLD.source_policy_version
            OR NEW.send_key IS DISTINCT FROM OLD.send_key
            OR NEW.created_by_membership_id IS DISTINCT FROM OLD.created_by_membership_id
            OR NEW.created_by_actor_id IS DISTINCT FROM OLD.created_by_actor_id
            OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Outbound message identity is immutable' USING ERRCODE = '23000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER outbound_messages_identity_immutable BEFORE UPDATE ON dokene.outbound_messages
    FOR EACH ROW EXECUTE FUNCTION dokene.prevent_outbound_message_identity_change();

CREATE TABLE dokene.outbound_message_approvals (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    message_id UUID NOT NULL,
    decision VARCHAR(8) NOT NULL,
    note VARCHAR(500),
    decided_at TIMESTAMPTZ NOT NULL,
    decided_by_membership_id UUID NOT NULL,
    decided_by_actor_id UUID NOT NULL,
    CONSTRAINT uq_outbound_message_approvals_message UNIQUE (tenant_id, message_id),
    CONSTRAINT fk_outbound_message_approvals_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbound_message_approvals_message FOREIGN KEY (tenant_id, message_id)
        REFERENCES dokene.outbound_messages(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbound_message_approvals_decision CHECK (decision IN ('APPROVE', 'REJECT'))
);

CREATE TABLE dokene.outbound_message_cancellations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    message_id UUID NOT NULL,
    status_from VARCHAR(24) NOT NULL,
    note VARCHAR(500),
    cancelled_at TIMESTAMPTZ NOT NULL,
    cancelled_by_membership_id UUID NOT NULL,
    cancelled_by_actor_id UUID NOT NULL,
    CONSTRAINT uq_outbound_message_cancellations_message UNIQUE (tenant_id, message_id),
    CONSTRAINT fk_outbound_message_cancellations_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbound_message_cancellations_message FOREIGN KEY (tenant_id, message_id)
        REFERENCES dokene.outbound_messages(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbound_message_cancellations_from CHECK (status_from IN ('PENDING_APPROVAL', 'APPROVED'))
);

CREATE TABLE dokene.outbound_send_attempts (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    message_id UUID NOT NULL,
    attempt_number INTEGER NOT NULL,
    send_key UUID NOT NULL,
    outcome VARCHAR(24) NOT NULL,
    failure_category VARCHAR(32),
    provider_message_id VARCHAR(128),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    requested_by_membership_id UUID NOT NULL,
    requested_by_actor_id UUID NOT NULL,
    CONSTRAINT uq_outbound_send_attempts_number UNIQUE (tenant_id, message_id, attempt_number),
    CONSTRAINT fk_outbound_send_attempts_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbound_send_attempts_message FOREIGN KEY (tenant_id, message_id)
        REFERENCES dokene.outbound_messages(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbound_send_attempts_number CHECK (attempt_number >= 1),
    CONSTRAINT ck_outbound_send_attempts_outcome CHECK (outcome IN (
        'STARTED', 'ACCEPTED', 'FAILED_PERMANENT', 'FAILED_TRANSIENT', 'OUTCOME_UNKNOWN')),
    CONSTRAINT ck_outbound_send_attempts_finished CHECK ((finished_at IS NULL) = (outcome = 'STARTED')),
    CONSTRAINT ck_outbound_send_attempts_failure CHECK (
        (failure_category IS NOT NULL) = (outcome IN ('FAILED_PERMANENT', 'FAILED_TRANSIENT'))),
    CONSTRAINT ck_outbound_send_attempts_failure_values CHECK (failure_category IS NULL OR failure_category IN (
        'INVALID_RECIPIENT', 'TEMPLATE_REJECTED', 'RATE_LIMITED', 'PROVIDER_UNAVAILABLE',
        'AUTHENTICATION', 'POLICY_VIOLATION', 'UNKNOWN')),
    -- An unknown outcome may still carry the id the provider returned before the connection dropped (T13).
    CONSTRAINT ck_outbound_send_attempts_provider_id CHECK (
        provider_message_id IS NULL OR outcome IN ('ACCEPTED', 'OUTCOME_UNKNOWN'))
);

-- Append only: sequence_number equals the message version after the row's transaction.
CREATE TABLE dokene.outbound_message_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    message_id UUID NOT NULL,
    sequence_number INTEGER NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    status_from VARCHAR(24),
    status_to VARCHAR(24) NOT NULL,
    applied BOOLEAN NOT NULL,
    actor_kind VARCHAR(8) NOT NULL,
    membership_id UUID,
    failure_category VARCHAR(32),
    attempt_number INTEGER,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_outbound_message_events_sequence UNIQUE (tenant_id, message_id, sequence_number),
    CONSTRAINT fk_outbound_message_events_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbound_message_events_message FOREIGN KEY (tenant_id, message_id)
        REFERENCES dokene.outbound_messages(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbound_message_events_sequence CHECK (sequence_number >= 1),
    CONSTRAINT ck_outbound_message_events_type CHECK (event_type IN (
        'SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'SEND_REQUESTED', 'SEND_ATTEMPT_STARTED',
        'SENT', 'SEND_FAILED', 'SEND_OUTCOME_UNKNOWN', 'DELIVERY_UPDATED')),
    CONSTRAINT ck_outbound_message_events_actor_kind CHECK (actor_kind IN ('MEMBER', 'SYSTEM', 'PROVIDER')),
    CONSTRAINT ck_outbound_message_events_membership CHECK ((actor_kind = 'MEMBER') = (membership_id IS NOT NULL)),
    CONSTRAINT ck_outbound_message_events_applied CHECK (applied OR status_from = status_to),
    CONSTRAINT ck_outbound_message_events_submitted CHECK ((event_type = 'SUBMITTED') = (status_from IS NULL)),
    CONSTRAINT ck_outbound_message_events_attempt_number CHECK (attempt_number IS NULL OR attempt_number >= 1),
    CONSTRAINT ck_outbound_message_events_failure_values CHECK (failure_category IS NULL OR failure_category IN (
        'INVALID_RECIPIENT', 'TEMPLATE_REJECTED', 'RATE_LIMITED', 'PROVIDER_UNAVAILABLE',
        'AUTHENTICATION', 'POLICY_VIOLATION', 'UNKNOWN'))
);

CREATE TABLE dokene.outbound_message_idempotency_keys (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    operation VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    message_id UUID NOT NULL,
    record_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_outbound_message_idempotency_keys UNIQUE (tenant_id, operation, idempotency_key),
    CONSTRAINT fk_outbound_message_idempotency_keys_tenant FOREIGN KEY (tenant_id)
        REFERENCES dokene.tenants(id) ON DELETE RESTRICT,
    CONSTRAINT fk_outbound_message_idempotency_keys_message FOREIGN KEY (tenant_id, message_id)
        REFERENCES dokene.outbound_messages(tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT ck_outbound_message_idempotency_keys_operation CHECK (operation IN (
        'SUBMIT', 'APPROVAL', 'CANCELLATION', 'SEND', 'RESOLUTION')),
    CONSTRAINT ck_outbound_message_idempotency_keys_key CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT ck_outbound_message_idempotency_keys_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_outbound_message_idempotency_keys_record CHECK ((operation = 'SUBMIT') = (record_id IS NULL))
);

REVOKE ALL ON TABLE dokene.outbound_messages, dokene.outbound_message_approvals,
    dokene.outbound_message_cancellations, dokene.outbound_send_attempts,
    dokene.outbound_message_events, dokene.outbound_message_idempotency_keys FROM PUBLIC;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.outbound_messages TO dokene_runtime;
GRANT SELECT, INSERT ON TABLE dokene.outbound_message_approvals TO dokene_runtime;
GRANT SELECT, INSERT ON TABLE dokene.outbound_message_cancellations TO dokene_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE dokene.outbound_send_attempts TO dokene_runtime;
GRANT SELECT, INSERT ON TABLE dokene.outbound_message_events TO dokene_runtime;
GRANT SELECT, INSERT ON TABLE dokene.outbound_message_idempotency_keys TO dokene_runtime;

ALTER TABLE dokene.outbound_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_messages FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_message_approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_message_approvals FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_message_cancellations ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_message_cancellations FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_send_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_send_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_message_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_message_events FORCE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_message_idempotency_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE dokene.outbound_message_idempotency_keys FORCE ROW LEVEL SECURITY;

CREATE POLICY outbound_messages_select_policy ON dokene.outbound_messages FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_messages_insert_policy ON dokene.outbound_messages FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_messages_update_policy ON dokene.outbound_messages FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_messages_delete_policy ON dokene.outbound_messages FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_messages_migration_policy ON dokene.outbound_messages FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

CREATE POLICY outbound_message_approvals_select_policy ON dokene.outbound_message_approvals FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_approvals_insert_policy ON dokene.outbound_message_approvals FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_approvals_update_policy ON dokene.outbound_message_approvals FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_approvals_delete_policy ON dokene.outbound_message_approvals FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_approvals_migration_policy ON dokene.outbound_message_approvals FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

CREATE POLICY outbound_message_cancellations_select_policy ON dokene.outbound_message_cancellations FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_cancellations_insert_policy ON dokene.outbound_message_cancellations FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_cancellations_update_policy ON dokene.outbound_message_cancellations FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_cancellations_delete_policy ON dokene.outbound_message_cancellations FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_cancellations_migration_policy ON dokene.outbound_message_cancellations FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

CREATE POLICY outbound_send_attempts_select_policy ON dokene.outbound_send_attempts FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_send_attempts_insert_policy ON dokene.outbound_send_attempts FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_send_attempts_update_policy ON dokene.outbound_send_attempts FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_send_attempts_delete_policy ON dokene.outbound_send_attempts FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_send_attempts_migration_policy ON dokene.outbound_send_attempts FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

CREATE POLICY outbound_message_events_select_policy ON dokene.outbound_message_events FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_events_insert_policy ON dokene.outbound_message_events FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_events_update_policy ON dokene.outbound_message_events FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_events_delete_policy ON dokene.outbound_message_events FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_events_migration_policy ON dokene.outbound_message_events FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);

CREATE POLICY outbound_message_idempotency_keys_select_policy ON dokene.outbound_message_idempotency_keys FOR SELECT TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_idempotency_keys_insert_policy ON dokene.outbound_message_idempotency_keys FOR INSERT TO dokene_runtime
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_idempotency_keys_update_policy ON dokene.outbound_message_idempotency_keys FOR UPDATE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id())
    WITH CHECK (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_idempotency_keys_delete_policy ON dokene.outbound_message_idempotency_keys FOR DELETE TO dokene_runtime
    USING (tenant_id = dokene.current_verified_tenant_id());
CREATE POLICY outbound_message_idempotency_keys_migration_policy ON dokene.outbound_message_idempotency_keys FOR ALL TO dokene_migration
    USING (true) WITH CHECK (true);
