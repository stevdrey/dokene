// ABOUTME: Unit tests for DurableIntegrationAuditAdapter, the integration audit port backed by the audit recorder.
// ABOUTME: Pins that a kill switch flip reaches outboundKillSwitchChanged and never the configuration event (AC-6).
package io.github.stevdrey.dokene.audit.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DurableIntegrationAuditAdapterTest {
    private final AuditRecorder recorder = mock(AuditRecorder.class);
    private final DurableIntegrationAuditAdapter adapter = new DurableIntegrationAuditAdapter(recorder);
    private final UUID integrationId = UUID.randomUUID();

    /** covers: AC-6 */
    @Test
    void anOutboundSwitchChangeIsRecordedAsAKillSwitchEventAndNothingElse() {
        adapter.outboundSwitchChanged(integrationId, true);

        verify(recorder).outboundKillSwitchChanged(integrationId, true);
        verifyNoMoreInteractions(recorder);
    }

    /** covers: AC-6 */
    @Test
    void switchingOffCarriesTheNewValueFalse() {
        adapter.outboundSwitchChanged(integrationId, false);

        verify(recorder).outboundKillSwitchChanged(integrationId, false);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void aConfigurationUpdateStaysTheIntegrationUpdatedEvent() {
        adapter.updated(integrationId, true);

        verify(recorder).integrationUpdated(integrationId, true);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void mappingRejectionsAndThrottlesAreDeniedManagePermissionEvents() {
        adapter.mappingRejected();
        adapter.mappingThrottled();

        verify(recorder).authorizationDenied(TenantPermission.INTEGRATION_MANAGE, AuditDenialReason.MAPPING_REJECTED);
        verify(recorder).authorizationDenied(TenantPermission.INTEGRATION_MANAGE, AuditDenialReason.RATE_LIMITED);
        verifyNoMoreInteractions(recorder);
    }
}
