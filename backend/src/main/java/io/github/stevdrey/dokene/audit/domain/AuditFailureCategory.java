// ABOUTME: Audit side mirror of the closed provider failure categories (ADR 0023 §1).
// ABOUTME: Names are intentionally identical to messaging.domain.FailureCategory.
package io.github.stevdrey.dokene.audit.domain;

public enum AuditFailureCategory {
    INVALID_RECIPIENT, TEMPLATE_REJECTED, RATE_LIMITED, PROVIDER_UNAVAILABLE, AUTHENTICATION, POLICY_VIOLATION, UNKNOWN
}
