-- ABOUTME: Fixed-text rules version (issue 268, ADR 0025 addendum): approval content records the version of the safety
-- ABOUTME: rules that checked it, and content checked under another version is not trusted until it is refreshed.

-- Expand phase only, like V31. An instance of the previous release never writes the new column, so the constraint
-- below cannot refuse its writes; the content it records has no version, which current instances read as no content
-- (not sendable until a current instance refreshes it). Every release from this one writes the version explicitly with
-- the content, so a row never keeps a version that a later write's content was not checked with. No backfill: content
-- recorded before this change was checked with earlier rules (an en_US template only with the Spanish vocabulary), so
-- every such approval must be refreshed.
-- The version is paired with the check time of the content it describes (approval_content_rules_checked_at). A
-- current writer stamps both with the content in one statement. A writer of the previous release rewrites the content
-- and its check time but never these two columns, so the pair no longer matches the content, and the trigger below
-- clears the version instead of letting the new content inherit it.
ALTER TABLE dokene.tenant_provider_templates
    ADD COLUMN approval_content_rules_version INTEGER,
    ADD COLUMN approval_content_rules_checked_at TIMESTAMPTZ;

ALTER TABLE dokene.tenant_provider_templates
    -- A rules version only ever accompanies the content it checked, together with that content's check time.
    ADD CONSTRAINT ck_tenant_provider_templates_content_rules_version CHECK (
        (approval_content_rules_version IS NULL) = (approval_content_rules_checked_at IS NULL)
        AND (approval_content_rules_version IS NULL
            OR (approval_body_text IS NOT NULL AND approval_content_rules_version > 0)));

-- Stale content goes with its version: any writer that changes the evidence without rewriting the content leaves the
-- check times apart. The version alone also goes whenever it no longer describes the stored content: a writer that
-- rewrote the content without restamping the version, or that removed the content (an edit), including a writer of
-- the previous release that knows nothing of these columns.
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
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION dokene.clear_stale_template_content() FROM PUBLIC;
