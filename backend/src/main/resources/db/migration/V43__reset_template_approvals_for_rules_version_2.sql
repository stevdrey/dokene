-- ABOUTME: Fixed-text rules version 2 (issue 443): a currency code glued inside an alphanumeric identifier is no longer
-- ABOUTME: read as an amount. Approvals checked under version 1 return to UNVERIFIED, as every rules bump requires (V32).

-- Content checked under version 1 is kept with its version, which TemplateMapping.sendable() no longer trusts. Without
-- this reset such an approval would read as APPROVED but unusable; UNVERIFIED says it needs a refresh, which checks the
-- text again under version 2. Only APPROVED was ever sendable. The stale-content trigger (V31, V32) clears content and
-- version once the evidence no longer matches it.
UPDATE dokene.tenant_provider_templates
SET approval_status = 'UNVERIFIED', approval_checked_at = NULL, approval_checked_business_account_id = NULL,
    approval_checked_version = NULL, approval_parameter_format = NULL, approval_parameter_checked_at = NULL
WHERE approval_status = 'APPROVED';
