// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a followup class that reaches a class in integration.secrets.
// ABOUTME: Never loaded by production code; the spec 0006 AC-7 negative proof imports it explicitly.
package io.github.stevdrey.dokene.followup.archfixture;

import io.github.stevdrey.dokene.integration.secrets.SecretSourceType;

@SuppressWarnings("unused")
public final class UpstreamDependsOnSecretSourceFixture {
    private UpstreamDependsOnSecretSourceFixture() {
    }

    static SecretSourceType source() { return SecretSourceType.DISABLED; }
}
