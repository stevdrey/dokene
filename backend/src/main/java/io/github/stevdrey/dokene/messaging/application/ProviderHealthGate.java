// ABOUTME: The last step of the send policy (ADR 0038): given the shared provider health, a send goes on, is refused
// ABOUTME: with PROVIDER_UNAVAILABLE and a retry delay, or first runs the read only recovery probe.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderUnavailableException;
import io.github.stevdrey.dokene.tenant.application.TenantContext;

public interface ProviderHealthGate {

    enum Admission {
        ALLOW, PROBE
    }

    /** A request probes at most once: the pass that follows its probe is refused rather than probing again. */
    enum Pass {
        FIRST, AFTER_PROBE
    }

    /**
     * Called inside the send transaction after every other policy step and the tenant rate check, before anything is
     * written. Reads the health row without locking it and never calls the provider. {@code PROBE} tells the caller
     * to roll that transaction back, so every send lock is released, and then call {@link #probe}.
     *
     * @throws MessagingProviderUnavailableException when the send must not go out now
     */
    Admission admit(MessagingProviderKey provider, Pass pass);

    /**
     * Takes the probe lease, runs the one read only provider call and stores its result. The caller holds no
     * transaction and no send lock. Returns normally when the caller may run its send flow again from the start.
     *
     * @throws MessagingProviderUnavailableException when the probe failed, could not run, or another one is running
     */
    void probe(MessagingProviderKey provider, TenantContext member, SenderRevision sender);
}
