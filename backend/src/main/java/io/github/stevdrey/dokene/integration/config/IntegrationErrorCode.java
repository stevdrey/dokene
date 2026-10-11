// ABOUTME: The closed codes of the integration routes (spec 0005 AC-8), rendered in the problem body.
// ABOUTME: One code per refusal; the HTTP status is derived in IntegrationProblemHandler.
package io.github.stevdrey.dokene.integration.config;

public enum IntegrationErrorCode {
    INVALID_INPUT, FORBIDDEN, STALE_VERSION, INTEGRATION_INCOMPLETE, SECRET_REF_UNRESOLVABLE,
    PHONE_NUMBER_ID_IN_USE, SECRET_REF_IN_USE
}
