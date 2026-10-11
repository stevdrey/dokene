// ABOUTME: Unit tests for the gate mapping table of spec 0005 AC-6: no row, four flag combinations, no context.
// ABOUTME: The repository is read on every call with the tenant from the context, never from input.
package io.github.stevdrey.dokene.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.messaging.application.OutboundGateStatus;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TenantIntegrationOutboundGateTest {
    private final TenantMessagingIntegrationRepository repository = mock(TenantMessagingIntegrationRepository.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final TenantIntegrationOutboundGate gate = new TenantIntegrationOutboundGate(repository, contexts);
    private final UUID tenantId = UUID.randomUUID();

    @ParameterizedTest
    @CsvSource({"false,false,INTEGRATION_DISABLED", "false,true,INTEGRATION_DISABLED",
            "true,false,OUTBOUND_DISABLED_FOR_TENANT", "true,true,ENABLED"})
    void derivesTheStatusFromTheTwoFlags(boolean enabled, boolean outbound, OutboundGateStatus expected) {
        when(contexts.currentTenantId()).thenReturn(Optional.of(new TenantId(tenantId)));
        when(repository.findByTenant(tenantId)).thenReturn(Optional.of(row(enabled, outbound)));

        assertThat(gate.check()).isEqualTo(expected);
        verify(repository).findByTenant(tenantId);
    }

    @Test
    void aMissingRowOrAMissingContextIsIntegrationDisabled() {
        when(contexts.currentTenantId()).thenReturn(Optional.of(new TenantId(tenantId)));
        when(repository.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThat(gate.check()).isEqualTo(OutboundGateStatus.INTEGRATION_DISABLED);

        when(contexts.currentTenantId()).thenReturn(Optional.empty());
        assertThat(gate.check()).isEqualTo(OutboundGateStatus.INTEGRATION_DISABLED);
        verify(repository).findByTenant(tenantId);
    }

    @Test
    void readsTheRowAgainOnEveryCall() {
        when(contexts.currentTenantId()).thenReturn(Optional.of(new TenantId(tenantId)));
        when(repository.findByTenant(tenantId)).thenReturn(Optional.of(row(true, true)), Optional.of(row(true, false)));

        assertThat(gate.check()).isEqualTo(OutboundGateStatus.ENABLED);
        assertThat(gate.check()).isEqualTo(OutboundGateStatus.OUTBOUND_DISABLED_FOR_TENANT);
    }

    private TenantMessagingIntegration row(boolean enabled, boolean outbound) {
        Instant now = Instant.now();
        return new TenantMessagingIntegration(tenantId, "100000000000000", "200000000000000", "+56922220000",
                SecretRef.parse("env:DOKENE_WA_TEST"), enabled, outbound, false, 1, now, now);
    }
}
