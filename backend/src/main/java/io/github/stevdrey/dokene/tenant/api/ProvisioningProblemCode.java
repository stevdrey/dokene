// ABOUTME: The closed set of machine readable refusal codes of POST /api/tenants (spec 0021).
// ABOUTME: A code tells the caller only which of its own limits applied, never anything about other identities.
package io.github.stevdrey.dokene.tenant.api;

public enum ProvisioningProblemCode {
    PROVISIONING_NOT_PERMITTED,
    WORKSPACE_QUOTA_EXCEEDED
}
