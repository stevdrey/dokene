// ABOUTME: Result of one read only reachability probe of a messaging provider (ADR 0038).
// ABOUTME: NO_RESULT means no network exchange happened, so it says nothing about the provider.
package io.github.stevdrey.dokene.messaging.domain;

public enum ProviderProbeResult {
    PASSED, PASSED_THROTTLED, FAILED, NO_RESULT
}
