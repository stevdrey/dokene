// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a followup class that imports messaging.
// ABOUTME: Never loaded by production code; the ArchUnit negative proof imports it explicitly.
package io.github.stevdrey.dokene.followup.archfixture;

import io.github.stevdrey.dokene.messaging.domain.MessageStatus;

@SuppressWarnings("unused")
public final class UpstreamDependsOnMessagingFixture {
    private UpstreamDependsOnMessagingFixture() {
    }

    static boolean terminal(MessageStatus status) {
        return status.terminal();
    }
}
