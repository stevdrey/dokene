// ABOUTME: Unit tests for the provider attributed delivery audit in TransactionalAuditRecorder.
// ABOUTME: The audit tenant must equal the trusted provider context tenant before any capability is signed.
package io.github.stevdrey.dokene.audit.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.domain.AuditMessageStatus;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class TransactionalAuditRecorderProviderBindingTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final AuditMetadata.MessageTransition METADATA = new AuditMetadata.MessageTransition(
            AuditMessageStatus.SENT, AuditMessageStatus.DELIVERED, null, null);

    private final JdbcAuditStore store = mock(JdbcAuditStore.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final ProviderContextProvider providerContexts = mock(ProviderContextProvider.class);
    private final DatabaseContextSigner signer = mock(DatabaseContextSigner.class);
    private final AuditExecutionContext execution = new AuditExecutionContext();
    private final TransactionalAuditRecorder recorder = new TransactionalAuditRecorder(store, contexts,
            providerContexts, signer, execution, Clock.fixed(NOW, ZoneOffset.UTC),
            mock(PlatformTransactionManager.class), new DatabaseUnavailability(Duration.ofSeconds(5)));
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final UUID messageId = UUID.randomUUID();

    @Test
    void refusesWhenNoProviderContextIsBound() {
        when(contexts.current()).thenReturn(Optional.empty());
        when(providerContexts.requireCurrent()).thenThrow(new TenantContextUnavailableException());

        assertThatThrownBy(this::recordDelivery).isInstanceOf(TenantContextUnavailableException.class);

        verifyNoInteractions(signer, store);
    }

    @Test
    void refusesWhenTheAuditTenantIsNotTheProviderContextTenant() {
        when(contexts.current()).thenReturn(Optional.empty());
        when(providerContexts.requireCurrent()).thenReturn(new ProviderContext(new TenantId(UUID.randomUUID())));

        assertThatThrownBy(this::recordDelivery).isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider attributed audit tenant does not match the provider context");

        verifyNoInteractions(signer, store);
    }

    @Test
    void refusesUnderAMemberContextEvenWithAMatchingProviderContext() {
        when(contexts.current()).thenReturn(Optional.of(mock(TenantContext.class)));
        when(providerContexts.requireCurrent()).thenReturn(new ProviderContext(tenantId));

        assertThatThrownBy(this::recordDelivery).isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider attributed audit cannot run under a member context");

        verifyNoInteractions(signer, store);
    }

    @Test
    void signsAndAppendsWhenTheProviderContextTenantMatches() throws Exception {
        SignedDatabaseContext capability = new SignedDatabaseContext("payload", "signature", NOW.plusSeconds(30));
        when(contexts.current()).thenReturn(Optional.empty());
        when(providerContexts.requireCurrent()).thenReturn(new ProviderContext(tenantId));
        when(signer.issueProviderAuditContext(tenantId)).thenReturn(capability);

        execution.callWithCorrelation(UUID.randomUUID(), () -> {
            recordDelivery();
            return null;
        });

        verify(signer).issueProviderAuditContext(tenantId);
        verify(store).append(any(), eq(capability));
    }

    private void recordDelivery() {
        recorder.messageDeliveryUpdated(tenantId, messageId, METADATA);
    }
}
