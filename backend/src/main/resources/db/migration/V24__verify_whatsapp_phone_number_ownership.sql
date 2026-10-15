-- ABOUTME: Persists proof of WhatsApp phone number ownership (issue 210): uniqueness binds verified mappings only, so an
-- ABOUTME: unverified claim never blocks the real owner, and webhook routing may resolve a tenant from verified ones only.

ALTER TABLE dokene.tenant_messaging_integrations
    ADD COLUMN ownership_verified_at TIMESTAMPTZ,
    ADD COLUMN ownership_verified_revision BIGINT,
    -- A proof holds for the credential it was made with; bumping credential_revision without clearing it fails here.
    ADD CONSTRAINT ck_tenant_messaging_integrations_ownership_verification CHECK (
        (ownership_verified_at IS NULL AND ownership_verified_revision IS NULL)
        OR (ownership_verified_at IS NOT NULL AND ownership_verified_revision = credential_revision));

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
