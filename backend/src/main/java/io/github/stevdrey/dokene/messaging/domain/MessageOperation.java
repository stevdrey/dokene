// ABOUTME: Idempotent operations scoped by (tenant, operation, key) per ADR 0023 §4.2.
// ABOUTME: Approve and reject share APPROVAL because the decision is part of the request fingerprint.
package io.github.stevdrey.dokene.messaging.domain;

public enum MessageOperation { SUBMIT, APPROVAL, CANCELLATION, SEND, RESOLUTION }
