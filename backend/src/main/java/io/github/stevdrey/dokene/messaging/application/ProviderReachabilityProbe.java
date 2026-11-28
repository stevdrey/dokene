// ABOUTME: Outbound port for the provider health recovery probe (ADR 0038): one read only call that shows whether the
// ABOUTME: provider answers at all. The integration module implements it. It is never a message send.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.tenant.domain.TenantId;

public interface ProviderReachabilityProbe {

    /**
     * Probes with the credential of {@code tenantId}, the tenant that is asking to send and is in context, and only
     * while its integration still is {@code sender}. Bounded by the adapter's own deadline. Never throws:
     * {@code NO_RESULT} means no network exchange happened, so nothing was learned about the provider.
     */
    ProviderProbeResult probe(TenantId tenantId, SenderRevision sender);
}
