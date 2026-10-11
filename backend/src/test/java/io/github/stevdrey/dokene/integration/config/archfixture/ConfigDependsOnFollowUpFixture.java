// ABOUTME: Deliberate violation used only to prove the integration.config ArchUnit rule matches (spec 0005 AC-10).
// ABOUTME: Never referenced by production code; it imports followup.domain from under integration.config.
package io.github.stevdrey.dokene.integration.config.archfixture;

import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;

public final class ConfigDependsOnFollowUpFixture {
    private ConfigDependsOnFollowUpFixture() {
    }

    public static Class<?> leaks() {
        return FollowUpStatus.class;
    }
}
