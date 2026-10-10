// ABOUTME: Answer of the outbound gate: the tenant may send, or which switch is closed.
// ABOUTME: Each closed value maps one to one onto a refusal code of ADR 0023 §4.3 step 4.
package io.github.stevdrey.dokene.messaging.application;

public enum OutboundGateStatus {
    ENABLED, OUTBOUND_DISABLED_FOR_TENANT, INTEGRATION_DISABLED
}
