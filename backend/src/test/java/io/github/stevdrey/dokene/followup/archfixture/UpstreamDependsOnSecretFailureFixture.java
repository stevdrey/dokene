// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a followup class that reaches the resolver failure shape.
// ABOUTME: Never loaded by production code; the spec 0006 AC-7 negative proof imports it explicitly.
package io.github.stevdrey.dokene.followup.archfixture;

import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;

@SuppressWarnings("unused")
public final class UpstreamDependsOnSecretFailureFixture {
    private UpstreamDependsOnSecretFailureFixture() {
    }

    static SecretUnavailableException.Category category(SecretUnavailableException denial) { return denial.category(); }
}
