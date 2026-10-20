-- ABOUTME: Stores the tenant outbound kill switch on the WhatsApp integration row (spec 0008, ADR 0023 §3.3).
-- ABOUTME: Default off, so every existing tenant reads as switched off; policies, grants and the V24 trigger are untouched.

ALTER TABLE dokene.tenant_messaging_integrations
    ADD COLUMN outbound_enabled BOOLEAN NOT NULL DEFAULT false;
