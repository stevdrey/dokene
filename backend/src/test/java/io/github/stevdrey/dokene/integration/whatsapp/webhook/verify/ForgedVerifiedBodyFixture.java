// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a verified body built outside the verifier.
// ABOUTME: Lives in the verify package because the constructor is package private; never loaded by production code.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

@SuppressWarnings("unused")
public final class ForgedVerifiedBodyFixture {
    private ForgedVerifiedBodyFixture() {
    }

    static VerifiedWebhookBody forge(byte[] unverified) {
        return new VerifiedWebhookBody(unverified);
    }
}
