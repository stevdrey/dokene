// ABOUTME: The five configurable fields of the integration record, used to name what an update changed.
// ABOUTME: Closed vocabulary: the audit trail stores these names and never a value (spec 0005 AC-4).
package io.github.stevdrey.dokene.integration.config;

public enum IntegrationField {
    PHONE_NUMBER_ID, BUSINESS_ACCOUNT_ID, DISPLAY_PHONE_NUMBER, SECRET_REF, ENABLED
}
