// ABOUTME: Port the dispatch service uses to report what one finished provider exchange said about the provider's
// ABOUTME: health (ADR 0038). Called only after the send's own outcome is committed; it can never change that outcome.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;

public interface ProviderHealthRecorder {
    /** Never throws: a signal that cannot be stored is logged and counted, and the health row keeps its last state. */
    void record(MessagingProviderKey provider, ProviderSignalKind kind);
}
