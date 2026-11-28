// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: an admission class that imports messaging.
// ABOUTME: Never loaded by production code; the ArchUnit negative proof imports it explicitly.
package io.github.stevdrey.dokene.admission.archfixture;

import io.github.stevdrey.dokene.messaging.domain.MessageStatus;

@SuppressWarnings("unused")
public final class AdmissionDependsOnMessagingFixture {
    private AdmissionDependsOnMessagingFixture() {
    }

    static boolean terminal(MessageStatus status) {
        return status.terminal();
    }
}
