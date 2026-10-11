// ABOUTME: Unit tests for the integration audit adapter: every field maps, the recorder gets closed metadata only.
// ABOUTME: Mirrors DurableMessageAuditAdapterTest in shape (spec 0005 AC-4, AC-5).
package io.github.stevdrey.dokene.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.IntegrationAuditField;
import io.github.stevdrey.dokene.integration.config.IntegrationField;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DurableIntegrationAuditAdapterTest {
    private final AuditRecorder recorder = mock(AuditRecorder.class);
    private final DurableIntegrationAuditAdapter adapter = new DurableIntegrationAuditAdapter(recorder);

    @Test
    void mapsEveryFieldAndForwardsTheEnabledFlag() {
        UUID tenantId = UUID.randomUUID();
        for (IntegrationField field : IntegrationField.values()) {
            assertThat(DurableIntegrationAuditAdapter.field(field).name()).isEqualTo(field.name());
        }

        adapter.integrationUpdated(tenantId, true, Set.of(IntegrationField.SECRET_REF, IntegrationField.ENABLED));
        adapter.outboundSwitchChanged(tenantId, false);

        verify(recorder).integrationUpdated(tenantId, new AuditMetadata.IntegrationUpdate(true,
                EnumSet.of(IntegrationAuditField.SECRET_REF, IntegrationAuditField.ENABLED)));
        verify(recorder).outboundKillSwitchChanged(tenantId, false);
    }
}
