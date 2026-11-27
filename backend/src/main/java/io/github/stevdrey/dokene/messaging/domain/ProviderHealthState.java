// ABOUTME: The four health states of a messaging provider (ADR 0038). UNKNOWN is the seed and what a row with no
// ABOUTME: recent signal reads as; only UNAVAILABLE and UNKNOWN can make the send gate refuse or probe.
package io.github.stevdrey.dokene.messaging.domain;

public enum ProviderHealthState {
    HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN
}
