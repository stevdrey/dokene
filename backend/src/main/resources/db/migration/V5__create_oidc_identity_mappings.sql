CREATE TABLE dokene.oidc_identity_mappings (
    identity_id UUID PRIMARY KEY,
    issuer VARCHAR(2048) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_authenticated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_oidc_identity_mappings_issuer_subject UNIQUE (issuer, subject),
    CONSTRAINT ck_oidc_identity_mappings_issuer_not_blank CHECK (btrim(issuer) <> ''),
    CONSTRAINT ck_oidc_identity_mappings_subject_not_blank CHECK (btrim(subject) <> ''),
    CONSTRAINT ck_oidc_identity_mappings_last_authentication CHECK (last_authenticated_at >= created_at)
);

REVOKE ALL ON TABLE dokene.oidc_identity_mappings FROM PUBLIC;
REVOKE ALL ON TABLE dokene.oidc_identity_mappings FROM dokene_runtime;

CREATE FUNCTION dokene.resolve_oidc_identity(requested_issuer TEXT, requested_subject TEXT)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, dokene
AS $$
DECLARE
    resolved_identity_id UUID;
    authenticated_at TIMESTAMP WITH TIME ZONE := clock_timestamp();
BEGIN
    IF requested_issuer IS NULL OR btrim(requested_issuer) = '' OR length(requested_issuer) > 2048
            OR requested_subject IS NULL OR btrim(requested_subject) = '' OR length(requested_subject) > 255 THEN
        RAISE EXCEPTION 'Invalid OIDC identity mapping';
    END IF;

    INSERT INTO dokene.oidc_identity_mappings AS existing (
        identity_id, issuer, subject, created_at, last_authenticated_at
    ) VALUES (
        gen_random_uuid(), requested_issuer, requested_subject, authenticated_at, authenticated_at
    )
    ON CONFLICT (issuer, subject) DO UPDATE
        SET last_authenticated_at = GREATEST(existing.last_authenticated_at, EXCLUDED.last_authenticated_at)
    RETURNING identity_id INTO resolved_identity_id;

    RETURN resolved_identity_id;
END;
$$;

REVOKE ALL ON FUNCTION dokene.resolve_oidc_identity(TEXT, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION dokene.resolve_oidc_identity(TEXT, TEXT) TO dokene_runtime;
