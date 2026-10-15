-- ABOUTME: Persists proof of WhatsApp phone number ownership (issue 210): uniqueness binds verified mappings only, so an
-- ABOUTME: unverified claim never blocks the real owner, and webhook routing may resolve a tenant from verified ones only.

ALTER TABLE dokene.tenant_messaging_integrations ADD COLUMN ownership_verified_at TIMESTAMPTZ;

-- A proof holds for one enabled phone number, business account and credential revision. Any write that changes one of
-- them, or disables the integration, must clear the proof in the same statement; one that keeps it is rejected. A new
-- row is never verified. The guard only rejects; it never rewrites a row behind the application's back.
CREATE FUNCTION dokene.guard_whatsapp_ownership_proof()
RETURNS TRIGGER
LANGUAGE plpgsql
SET search_path = pg_catalog, dokene
AS $$
BEGIN
    IF NEW.ownership_verified_at IS NOT NULL AND (TG_OP = 'INSERT' OR NOT NEW.enabled
            OR NEW.phone_number_id IS DISTINCT FROM OLD.phone_number_id
            OR NEW.business_account_id IS DISTINCT FROM OLD.business_account_id
            OR NEW.credential_revision IS DISTINCT FROM OLD.credential_revision) THEN
        RAISE EXCEPTION 'WhatsApp ownership proof does not match the integration'
            USING ERRCODE = 'check_violation', CONSTRAINT = 'tg_tenant_messaging_integrations_ownership_proof';
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION dokene.guard_whatsapp_ownership_proof() FROM PUBLIC;

CREATE TRIGGER tg_tenant_messaging_integrations_ownership_proof
    BEFORE INSERT OR UPDATE ON dokene.tenant_messaging_integrations
    FOR EACH ROW EXECUTE FUNCTION dokene.guard_whatsapp_ownership_proof();

-- Every existing row is unverified, so replacing the global key with one over verified rows cannot fail.
ALTER TABLE dokene.tenant_messaging_integrations DROP CONSTRAINT uq_tenant_messaging_integrations_phone_number;
CREATE UNIQUE INDEX uq_tenant_messaging_integrations_verified_phone_number
    ON dokene.tenant_messaging_integrations (provider, phone_number_id)
    WHERE ownership_verified_at IS NOT NULL;

-- Webhook ingress (#139) has no tenant context yet; this is its only way to map a phone number id to a tenant. It
-- returns a tenant id for a verified, enabled mapping only and never any other column.
CREATE FUNCTION dokene.resolve_verified_whatsapp_tenant(requested_phone_number_id TEXT)
RETURNS UUID
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    resolved_tenant_id UUID;
BEGIN
    IF requested_phone_number_id IS NULL OR requested_phone_number_id !~ '^[0-9]{5,32}$' THEN
        RETURN NULL;
    END IF;

    SELECT tenant_id INTO resolved_tenant_id
    FROM dokene.tenant_messaging_integrations
    WHERE provider = 'META_WHATSAPP' AND phone_number_id = requested_phone_number_id
        AND ownership_verified_at IS NOT NULL AND enabled;

    RETURN resolved_tenant_id;
END;
$$;

REVOKE ALL ON FUNCTION dokene.resolve_verified_whatsapp_tenant(TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.resolve_verified_whatsapp_tenant(TEXT) TO dokene_runtime;
