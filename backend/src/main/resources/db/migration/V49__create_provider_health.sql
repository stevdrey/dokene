-- ABOUTME: Issue 471 (ADR 0038): one platform wide health row per messaging provider, shared by every backend
-- ABOUTME: instance. It holds states, counters and times only: no tenant, message, phone or credential data.

-- Expand only: a backend of the previous release never reads or writes the new table.
CREATE TABLE dokene.provider_health (
    provider TEXT NOT NULL,
    state TEXT NOT NULL,
    state_changed_at TIMESTAMPTZ NOT NULL,
    -- The last send signal or probe result.
    last_signal_at TIMESTAMPTZ,
    last_signal_kind TEXT,
    consecutive_outage_signals INTEGER NOT NULL DEFAULT 0,
    -- Start of the current outage run; null while none is open.
    first_outage_signal_at TIMESTAMPTZ,
    consecutive_successes INTEGER NOT NULL DEFAULT 0,
    -- Set only while the state is UNAVAILABLE.
    cool_down_until TIMESTAMPTZ,
    cool_down_step INTEGER NOT NULL DEFAULT 0,
    -- A probe holds the lease while this is in the future; the exact value is also the lease token.
    probe_lease_until TIMESTAMPTZ,
    CONSTRAINT pk_provider_health PRIMARY KEY (provider),
    CONSTRAINT ck_provider_health_provider CHECK (provider IN ('META_WHATSAPP')),
    CONSTRAINT ck_provider_health_state CHECK (state IN ('HEALTHY', 'DEGRADED', 'UNAVAILABLE', 'UNKNOWN')),
    CONSTRAINT ck_provider_health_last_signal_kind
        CHECK (last_signal_kind IN ('SUCCESS', 'THROTTLED', 'OUTAGE')),
    CONSTRAINT ck_provider_health_counters
        CHECK (consecutive_outage_signals >= 0 AND consecutive_successes >= 0 AND cool_down_step >= 0)
);

-- Nothing is known about a provider until it is probed or, in observe mode, a send says so.
INSERT INTO dokene.provider_health (provider, state, state_changed_at)
VALUES ('META_WHATSAPP', 'UNKNOWN', now());

-- Platform state, not tenant data: there is no tenant_id column and so no tenant row level security policy. The
-- runtime reads and updates the seeded row; it can neither add a provider nor delete one.
REVOKE ALL ON TABLE dokene.provider_health FROM PUBLIC;
GRANT SELECT, UPDATE ON TABLE dokene.provider_health TO dokene_runtime;
