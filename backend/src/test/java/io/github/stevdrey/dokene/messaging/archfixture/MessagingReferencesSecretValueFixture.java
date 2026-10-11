// ABOUTME: Deliberate violation used only to prove the SecretValue ArchUnit rule matches (spec 0005 AC-10).
// ABOUTME: Never referenced by production code; it references SecretValue from outside integration.
package io.github.stevdrey.dokene.messaging.archfixture;

import io.github.stevdrey.dokene.integration.config.SecretValue;

public final class MessagingReferencesSecretValueFixture {
    private MessagingReferencesSecretValueFixture() {
    }

    public static Class<?> leaks() {
        return SecretValue.class;
    }
}
