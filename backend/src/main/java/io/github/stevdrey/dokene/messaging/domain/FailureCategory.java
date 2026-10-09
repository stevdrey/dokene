// ABOUTME: Closed set of normalized provider failure reasons (ADR 0023 §1).
// ABOUTME: Adapters map provider errors here so no provider text crosses the port.
package io.github.stevdrey.dokene.messaging.domain;

public enum FailureCategory {
    INVALID_RECIPIENT, TEMPLATE_REJECTED, RATE_LIMITED, PROVIDER_UNAVAILABLE, AUTHENTICATION, POLICY_VIOLATION, UNKNOWN
}
