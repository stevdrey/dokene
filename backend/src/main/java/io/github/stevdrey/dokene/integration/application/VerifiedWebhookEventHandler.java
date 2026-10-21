// ABOUTME: The only entry point from webhook parsing into message delivery tracking (ADR 0024 §3).
// ABOUTME: Accepts only VerifiedWebhookEvent; the architecture test keeps other integration classes off the sink.
package io.github.stevdrey.dokene.integration.application;

/**
 * Implementations bind {@code ProviderContext} for the event's tenant and call {@code DeliveryStatusSink}; they are
 * the only classes outside messaging allowed to depend on that sink, so a raw provider report cannot skip signature
 * verification, stored-identifier tenant resolution or dedup.
 */
public interface VerifiedWebhookEventHandler {
    /** Runs inside the caller's transaction, so the inbox row and the message change commit or roll back together. */
    Handling handle(VerifiedWebhookEvent event);

    /** APPLIED moved the message; IGNORED recorded a non applied event; NOT_FOUND matched no message in the tenant. */
    enum Handling { APPLIED, IGNORED, NOT_FOUND }
}
