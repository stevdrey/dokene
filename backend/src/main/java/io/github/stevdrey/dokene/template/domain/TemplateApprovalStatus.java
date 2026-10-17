// ABOUTME: Provider-reported approval state of a mapped template; UNVERIFIED means no provider evidence is current.
// ABOUTME: Only the verification path assigns anything but UNVERIFIED; SCHEMA_* are derived by Dokene, never reported.
package io.github.stevdrey.dokene.template.domain;

public enum TemplateApprovalStatus {
    UNVERIFIED, APPROVED, PENDING, REJECTED, PAUSED, DISABLED, DELETED, NOT_FOUND,
    /** Approved by the provider, but its placeholders do not match the stored parameter names. */
    SCHEMA_MISMATCH,
    /** Approved by the provider, but it needs a component or parameter kind Dokene does not send. */
    SCHEMA_UNSUPPORTED,
    /** Approved by the provider, but its component metadata could not be interpreted. */
    SCHEMA_UNREADABLE;

    /** True for outcomes Dokene derives from the provider's component schema rather than its reported status. */
    public boolean derivedFromSchema() {
        return this == SCHEMA_MISMATCH || this == SCHEMA_UNSUPPORTED || this == SCHEMA_UNREADABLE;
    }
}
