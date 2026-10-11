// ABOUTME: Audit side vocabulary for the integration fields an INTEGRATION_UPDATED row names (spec 0005 AC-4).
// ABOUTME: Mirrors integration.config.IntegrationField so the audit core never depends on the integration module.
package io.github.stevdrey.dokene.audit.domain;

public enum IntegrationAuditField {
    PHONE_NUMBER_ID, BUSINESS_ACCOUNT_ID, DISPLAY_PHONE_NUMBER, SECRET_REF, ENABLED
}
