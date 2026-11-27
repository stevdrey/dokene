// ABOUTME: What one finished provider exchange says about the provider's health (ADR 0038), never about the message.
// ABOUTME: A definitive answer, tenant specific refusals included, is SUCCESS: the provider was there to give it.
package io.github.stevdrey.dokene.messaging.domain;

public enum ProviderSignalKind {
    SUCCESS, THROTTLED, OUTAGE
}
