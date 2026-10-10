// ABOUTME: Outcome recorded on a send attempt row.
// ABOUTME: STARTED is the only outcome without a finish time.
package io.github.stevdrey.dokene.messaging.domain;

public enum SendAttemptOutcome { STARTED, ACCEPTED, FAILED_PERMANENT, FAILED_TRANSIENT, OUTCOME_UNKNOWN }
