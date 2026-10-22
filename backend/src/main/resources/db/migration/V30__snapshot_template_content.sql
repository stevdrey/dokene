-- ABOUTME: Template content snapshot (issue 268, ADR 0025 §2a): approval evidence keeps the template's fixed header,
-- ABOUTME: body, footer and buttons, and approvals recorded without them return to UNVERIFIED.

-- Expand phase only, like V27: instances still running the previous release never write the new columns, so none of
-- the constraints below can refuse their writes. The application trusts stored content only when
-- approval_content_checked_at equals approval_checked_at, so an older instance that rewrites the evidence (even an
-- APPROVED refresh with a verified format) silently invalidates the content instead of inheriting it, and that
-- approval is not sendable until a current instance refreshes it.
ALTER TABLE dokene.tenant_provider_templates
    ADD COLUMN approval_header_text TEXT,
    ADD COLUMN approval_body_text TEXT,
    ADD COLUMN approval_footer_text TEXT,
    -- One entry per button, in order, in three aligned arrays; the value is NULL for a quick reply.
    ADD COLUMN approval_button_types TEXT[],
    ADD COLUMN approval_button_texts TEXT[],
    ADD COLUMN approval_button_values TEXT[],
    ADD COLUMN approval_content_checked_at TIMESTAMPTZ;

-- Approvals recorded before this change cannot show the reviewer what is sent, so they are not trusted: those
-- mappings return to UNVERIFIED and must be refreshed. Only APPROVED was ever sendable, so no other row changes.
UPDATE dokene.tenant_provider_templates
SET approval_status = 'UNVERIFIED', approval_checked_at = NULL, approval_checked_business_account_id = NULL,
    approval_checked_version = NULL, approval_parameter_format = NULL, approval_parameter_checked_at = NULL
WHERE approval_status = 'APPROVED';

ALTER TABLE dokene.tenant_provider_templates
    -- The body is the one required part; header and footer only ever accompany it, with the check time.
    ADD CONSTRAINT ck_tenant_provider_templates_content CHECK (
        (approval_body_text IS NULL) = (approval_content_checked_at IS NULL)
        AND (approval_body_text IS NOT NULL OR (approval_header_text IS NULL AND approval_footer_text IS NULL))),
    ADD CONSTRAINT ck_tenant_provider_templates_content_length CHECK (
        coalesce(char_length(approval_header_text), 0) <= 4096
        AND coalesce(char_length(approval_body_text), 0) <= 4096
        AND coalesce(char_length(approval_footer_text), 0) <= 4096),
    -- Buttons are stored exactly with a body, as aligned arrays of at most ten known, bounded entries. A value is
    -- present exactly for phone and URL buttons; text is never NULL.
    ADD CONSTRAINT ck_tenant_provider_templates_buttons CHECK (
        (approval_button_types IS NULL) = (approval_body_text IS NULL)
        AND (approval_button_texts IS NULL) = (approval_body_text IS NULL)
        AND (approval_button_values IS NULL) = (approval_body_text IS NULL)
        AND coalesce(cardinality(approval_button_types), 0) <= 10
        AND cardinality(approval_button_texts) IS NOT DISTINCT FROM cardinality(approval_button_types)
        AND cardinality(approval_button_values) IS NOT DISTINCT FROM cardinality(approval_button_types)
        AND approval_button_types <@ ARRAY['QUICK_REPLY', 'PHONE_NUMBER', 'URL']::TEXT[]
        AND array_position(approval_button_texts, NULL) IS NULL
        AND coalesce(char_length(array_to_string(approval_button_texts, '')), 0)
            + coalesce(char_length(array_to_string(approval_button_values, '')), 0) <= 81920);
