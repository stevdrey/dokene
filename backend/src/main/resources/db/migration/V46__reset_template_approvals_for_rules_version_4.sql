-- ABOUTME: Fixed-text rules version 4 (issue 456): a repeated CAD code or a multiplier prefix ("55cad2cad",
-- ABOUTME: "2x55cad2", "0x55cad2") no longer hides an amount. Approvals checked under version 3 return to UNVERIFIED.

-- Version 4 refuses fixed text that version 3 accepted. Content checked under version 3 is kept with its version, which
-- TemplateMapping.sendable() no longer trusts. Without this reset such an approval would read as APPROVED but unusable;
-- UNVERIFIED says it needs a refresh, which checks the text again under version 4 and the tenant's currency. Only
-- APPROVED was ever sendable. The stale-content trigger (V45) clears content, version and currency once the evidence
-- no longer matches it.
UPDATE dokene.tenant_provider_templates
SET approval_status = 'UNVERIFIED', approval_checked_at = NULL, approval_checked_business_account_id = NULL,
    approval_checked_version = NULL, approval_parameter_format = NULL, approval_parameter_checked_at = NULL
WHERE approval_status = 'APPROVED';
