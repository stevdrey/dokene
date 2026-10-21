// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a followup class that reaches the webhook verifier.
// ABOUTME: Never loaded by production code; the spec 0007 AC-9 negative proof imports it explicitly.
package io.github.stevdrey.dokene.followup.archfixture;

import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException;

@SuppressWarnings("unused")
public final class UpstreamDependsOnWebhookVerifyFixture {
    private UpstreamDependsOnWebhookVerifyFixture() {
    }

    static WebhookVerificationException.Reason reason() { return WebhookVerificationException.Reason.WRONG_MODE; }
}
