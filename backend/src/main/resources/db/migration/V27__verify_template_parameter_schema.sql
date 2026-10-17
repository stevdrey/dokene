-- ABOUTME: Template parameter schema evidence (issue 218, ADR 0025 §2a): adds the SCHEMA_* outcomes, records the
-- ABOUTME: verified parameter format with approvals, and drops approvals that only proved status, name and language.

-- Expand phase only: instances still running the previous release keep working during a rolling deploy. They never
-- write SCHEMA_* or the two new columns, so none of the constraints below can refuse their writes. The application
-- trusts a stored format only when approval_parameter_checked_at equals approval_checked_at, so an older instance
-- that rewrites the evidence silently invalidates the format instead of inheriting it. Once every instance runs this
-- release, a later migration may add the strict "format exactly when APPROVED" CHECK and drop the check-time column.
ALTER TABLE dokene.tenant_provider_templates
    DROP CONSTRAINT ck_tenant_provider_templates_status,
    ALTER COLUMN approval_status TYPE VARCHAR(32),
    ADD COLUMN approval_parameter_format VARCHAR(16),
    ADD COLUMN approval_parameter_checked_at TIMESTAMPTZ;

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
    -- A format is only ever written together with the time of the schema check that produced it.
    ADD CONSTRAINT ck_tenant_provider_templates_parameter_format CHECK (
        (approval_parameter_format IS NULL OR approval_parameter_format IN ('POSITIONAL', 'NAMED'))
        AND (approval_parameter_format IS NULL) = (approval_parameter_checked_at IS NULL));
