// ABOUTME: Port that turns a secret reference into its value on the server, read at the moment of the call.
// ABOUTME: Called once at save time to refuse a typo early and again at send time; never cached (spec 0005 AC-7).
package io.github.stevdrey.dokene.integration.config;

import java.util.Optional;

public interface IntegrationSecretResolver {
    /** Empty when the reference names nothing, or names a blank value. */
    Optional<SecretValue> resolve(SecretRef ref);
}
