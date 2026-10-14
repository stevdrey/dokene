// ABOUTME: Provider-reported approval state of a mapped template; UNVERIFIED means no provider evidence is current.
// ABOUTME: Only the verification path assigns anything but UNVERIFIED; an operator can never supply it.
package io.github.stevdrey.dokene.template.domain;

public enum TemplateApprovalStatus {
    UNVERIFIED, APPROVED, PENDING, REJECTED, PAUSED, DISABLED, DELETED, NOT_FOUND
}
