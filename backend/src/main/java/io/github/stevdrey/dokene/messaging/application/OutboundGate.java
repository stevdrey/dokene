// ABOUTME: Port for the tenant level outbound switches (ADR 0023 §4.3 step 4), read at send time only.
// ABOUTME: Feature 5 replaces the property stub with the tenant integration configuration.
package io.github.stevdrey.dokene.messaging.application;

public interface OutboundGate {
    /** Evaluated under the current tenant context; never takes the tenant from input. */
    OutboundGateStatus check();
}
