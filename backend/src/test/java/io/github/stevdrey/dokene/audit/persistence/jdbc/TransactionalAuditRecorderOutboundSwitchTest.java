// ABOUTME: Unit tests for the OUTBOUND_KILL_SWITCH_CHANGED event in TransactionalAuditRecorder.
// ABOUTME: The row is attributed to the acting member, carries only the boolean and joins the caller's transaction (AC-6).
package io.github.stevdrey.dokene.audit.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.AuditOutcome;
import io.github.stevdrey.dokene.audit.domain.AuditTarget;
import io.github.stevdrey.dokene.integration.secrets.LogCapture;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

class TransactionalAuditRecorderOutboundSwitchTest {
    private static final Instant NOW = Instant.parse("2026-10-19T09:30:00Z");

    private final JdbcAuditStore store = mock(JdbcAuditStore.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final DatabaseContextSigner signer = mock(DatabaseContextSigner.class);
    private final AuditExecutionContext execution = new AuditExecutionContext();
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final TransactionalAuditRecorder recorder = new TransactionalAuditRecorder(store, contexts,
            mock(ProviderContextProvider.class), signer, execution, Clock.fixed(NOW, ZoneOffset.UTC), transactions);
    private final TenantContext member = new TenantContext(TenantId.random(), new IdentityId(UUID.randomUUID()),
            TenantMembershipId.random(), TenantRole.OWNER);
    private final UUID integrationId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    /** covers: AC-6 */
    @Test
    void writesOneSuccessEventAttributedToTheActingMemberWithOnlyTheBoolean() throws Exception {
        SignedDatabaseContext capability = new SignedDatabaseContext("payload", "signature", NOW.plusSeconds(30));
        when(contexts.requireCurrent()).thenReturn(member);
        when(signer.issueAuditContext(member.tenantId(), member.identityId(), member.membershipId()))
                .thenReturn(capability);

        execution.runWithCorrelation(correlationId, () -> recorder.outboundKillSwitchChanged(integrationId, true));

        ArgumentCaptor<AuditEvent> appended = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).append(appended.capture(), eq(capability));
        AuditEvent event = appended.getValue();
        assertThat(event.type()).isEqualTo(AuditEventType.OUTBOUND_KILL_SWITCH_CHANGED);
        assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.target()).isEqualTo(new AuditTarget(AuditTarget.Type.INTEGRATION, integrationId));
        assertThat(event.metadata()).isEqualTo(new AuditMetadata.IntegrationToggle(true));
        assertThat(event.tenantId()).isEqualTo(member.tenantId());
        assertThat(event.actorId()).isEqualTo(member.identityId());
        assertThat(event.membershipId()).isEqualTo(member.membershipId());
        assertThat(event.correlationId()).isEqualTo(correlationId);
        assertThat(event.timestamp()).isEqualTo(NOW);
    }

    /** covers: AC-6 */
    @Test
    void switchingOffRecordsFalse() throws Exception {
        when(contexts.requireCurrent()).thenReturn(member);
        when(signer.issueAuditContext(any(), any(), any()))
                .thenReturn(new SignedDatabaseContext("payload", "signature", NOW.plusSeconds(30)));

        execution.runWithCorrelation(correlationId, () -> recorder.outboundKillSwitchChanged(integrationId, false));

        ArgumentCaptor<AuditEvent> appended = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).append(appended.capture(), any());
        assertThat(appended.getValue().metadata()).isEqualTo(new AuditMetadata.IntegrationToggle(false));
    }

    /** covers: AC-6 */
    @Test
    void joinsTheCallersTransactionInsteadOfOpeningItsOwn() throws Exception {
        when(contexts.requireCurrent()).thenReturn(member);
        when(signer.issueAuditContext(any(), any(), any()))
                .thenReturn(new SignedDatabaseContext("payload", "signature", NOW.plusSeconds(30)));

        execution.runWithCorrelation(correlationId, () -> recorder.outboundKillSwitchChanged(integrationId, true));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactions).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    /** covers: AC-6 */
    @Test
    void refusesWithoutAMemberContextAndSignsNothing() {
        when(contexts.requireCurrent()).thenThrow(new TenantContextUnavailableException());

        assertThatThrownBy(() -> execution.runWithCorrelation(correlationId,
                () -> recorder.outboundKillSwitchChanged(integrationId, true)))
                .isInstanceOf(TenantContextUnavailableException.class);

        verifyNoInteractions(signer, store);
    }

    /** covers: AC-6 */
    @Test
    void aStoreFailureSurfacesAsAuditPersistenceExceptionWithoutLeakingTheDriverMessage() throws Exception {
        when(contexts.requireCurrent()).thenReturn(member);
        when(signer.issueAuditContext(any(), any(), any()))
                .thenReturn(new SignedDatabaseContext("payload", "signature", NOW.plusSeconds(30)));
        doThrow(new IllegalStateException("INSERT INTO audit_events ... secret-ref-123")).when(store)
                .append(any(), any());

        try (LogCapture logs = new LogCapture()) {
            assertThatThrownBy(() -> execution.runWithCorrelation(correlationId,
                    () -> recorder.outboundKillSwitchChanged(integrationId, true)))
                    .isInstanceOf(AuditPersistenceException.class)
                    .hasNoCause()
                    .hasMessage("Audit persistence unavailable");

            assertThat(logs.text()).contains("Audit persistence failed")
                    .doesNotContain("secret-ref-123").doesNotContain("INSERT INTO");
        }
    }
}
