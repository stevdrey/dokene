-- ABOUTME: Fixed-text rules version 3 (issue 452): a glued CAD-like hex token is an identifier for a tenant that does not
-- ABOUTME: operate in CAD, so approval content records the tenant currency it was checked under, and V43 approvals reset.

-- The currency is written together with the rules version, by every writer of this release, in the same statement as
-- the content (JdbcTemplateMappingRepository.updateApproval): NULL with a version means the tenant had no currency
-- set. TemplateMapping.sendable() trusts the content only while the tenant still operates in that same currency, so a
-- currency change makes the approval unsendable until a refresh checks the text again under the new one. A writer of
-- the previous release never writes this column; the content it records carries version 2, which this release never
-- sends, so a currency left over from an earlier write cannot vouch for it.
ALTER TABLE dokene.tenant_provider_templates
    ADD COLUMN approval_content_currency VARCHAR(3),
    -- A currency only ever accompanies the rules version that checked the content under it.
    ADD CONSTRAINT ck_tenant_provider_templates_content_currency CHECK (
        approval_content_currency IS NULL
        OR (approval_content_rules_version IS NOT NULL AND approval_content_currency ~ '^[A-Z]{3}$'));

-- Content checked under version 2 is kept with its version, which TemplateMapping.sendable() no longer trusts. Without
-- this reset such an approval would read as APPROVED but unusable; UNVERIFIED says it needs a refresh, which checks the
-- text again under version 3 and the tenant's currency. Only APPROVED was ever sendable. The stale-content trigger
-- (V31, V32, below) clears content, version and currency once the evidence no longer matches it.
UPDATE dokene.tenant_provider_templates
SET approval_status = 'UNVERIFIED', approval_checked_at = NULL, approval_checked_business_account_id = NULL,
    approval_checked_version = NULL, approval_parameter_format = NULL, approval_parameter_checked_at = NULL
WHERE approval_status = 'APPROVED';

-- V32's function, unchanged except that the currency goes whenever the rules version does, so a writer that clears the
-- version (an edit, or one of the previous release) never leaves a currency the constraint above refuses.
CREATE OR REPLACE FUNCTION dokene.clear_stale_template_content()
RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog, dokene AS $$
BEGIN
    IF NEW.approval_content_checked_at IS DISTINCT FROM NEW.approval_checked_at THEN
        NEW.approval_header_text := NULL;
        NEW.approval_body_text := NULL;
        NEW.approval_footer_text := NULL;
        NEW.approval_button_types := NULL;
        NEW.approval_button_texts := NULL;
        NEW.approval_button_values := NULL;
        NEW.approval_content_parameter_format := NULL;
        NEW.approval_content_checked_at := NULL;
    END IF;
    IF NEW.approval_body_text IS NULL
            OR NEW.approval_content_rules_checked_at IS DISTINCT FROM NEW.approval_content_checked_at THEN
        NEW.approval_content_rules_version := NULL;
        NEW.approval_content_rules_checked_at := NULL;
    END IF;
    IF NEW.approval_content_rules_version IS NULL THEN
        NEW.approval_content_currency := NULL;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION dokene.clear_stale_template_content() FROM PUBLIC;
