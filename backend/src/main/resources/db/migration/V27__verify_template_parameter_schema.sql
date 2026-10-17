-- ABOUTME: Template parameter schema evidence (issue 218, ADR 0025 §2a): adds the SCHEMA_* outcomes, records the
-- ABOUTME: verified parameter format with approvals, and drops approvals that only proved status, name and language.

ALTER TABLE dokene.tenant_provider_templates
    DROP CONSTRAINT ck_tenant_provider_templates_status,
    ALTER COLUMN approval_status TYPE VARCHAR(32),
    ADD COLUMN approval_parameter_format VARCHAR(16);

-- Evidence recorded before this change never checked the template's placeholders, so it is not trusted: those
-- mappings return to UNVERIFIED and must be refreshed. Only APPROVED was ever sendable, so no other row changes.
UPDATE dokene.tenant_provider_templates
SET approval_status = 'UNVERIFIED', approval_checked_at = NULL, approval_checked_business_account_id = NULL,
    approval_checked_version = NULL
WHERE approval_status = 'APPROVED';

ALTER TABLE dokene.tenant_provider_templates
    ADD CONSTRAINT ck_tenant_provider_templates_status CHECK (approval_status IN (
        'UNVERIFIED', 'APPROVED', 'PENDING', 'REJECTED', 'PAUSED', 'DISABLED', 'DELETED', 'NOT_FOUND',
        'SCHEMA_MISMATCH', 'SCHEMA_UNSUPPORTED', 'SCHEMA_UNREADABLE')),
    -- Only an approval verified against the template's schema carries the format the send path must use.
    ADD CONSTRAINT ck_tenant_provider_templates_parameter_format CHECK (
        (approval_status = 'APPROVED') = (approval_parameter_format IS NOT NULL)
        AND (approval_parameter_format IS NULL OR approval_parameter_format IN ('POSITIONAL', 'NAMED')));
