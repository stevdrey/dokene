// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a followup class that reaches the secret resolver port.
// ABOUTME: Never loaded by production code; the spec 0006 AC-7 negative proof imports it explicitly.
package io.github.stevdrey.dokene.followup.archfixture;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;

@SuppressWarnings("unused")
public final class UpstreamDependsOnSecretPortFixture {
    private UpstreamDependsOnSecretPortFixture() {
    }

    static IntegrationSecretResolver.SecretPurpose purpose() { return IntegrationSecretResolver.SecretPurpose.APP_SECRET; }
}
