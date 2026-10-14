// ABOUTME: Deliberate violation fixture for ModuleDependencyArchitectureTest: a "verified" event built elsewhere.
// ABOUTME: Never loaded by production code; the ArchUnit negative proof imports it explicitly.
package io.github.stevdrey.dokene.integration.archfixture;

import io.github.stevdrey.dokene.integration.application.VerifiedWebhookEvent;
import io.github.stevdrey.dokene.messaging.domain.DeliveryStatusReport;
import io.github.stevdrey.dokene.tenant.domain.TenantId;

@SuppressWarnings("unused")
public final class FabricatedWebhookEventFixture {
    private FabricatedWebhookEventFixture() {
    }

    static VerifiedWebhookEvent fabricate(TenantId tenantFromPayload, DeliveryStatusReport report) {
        return new VerifiedWebhookEvent(VerifiedWebhookEvent.Provider.META_WHATSAPP, "evt-1", tenantFromPayload,
                report);
    }
}
