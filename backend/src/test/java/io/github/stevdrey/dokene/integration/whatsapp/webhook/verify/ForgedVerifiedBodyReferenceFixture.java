// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a verified body forged through a constructor reference.
// ABOUTME: Proves the construction rule sees VerifiedWebhookBody::new, not only the new keyword; never loaded by production code.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import java.util.function.Function;

@SuppressWarnings("unused")
public final class ForgedVerifiedBodyReferenceFixture {
    private ForgedVerifiedBodyReferenceFixture() {
    }

    static VerifiedWebhookBody forge(byte[] unverified) {
        Function<byte[], VerifiedWebhookBody> constructor = VerifiedWebhookBody::new;
        return constructor.apply(unverified);
    }
}
