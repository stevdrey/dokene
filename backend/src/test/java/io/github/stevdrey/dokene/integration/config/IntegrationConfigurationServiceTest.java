// ABOUTME: Unit tests for the validation rules and the refusal order of the integration service (spec 0005 AC-3, AC-4).
// ABOUTME: Shape refusals never open a transaction; semantic refusals run after the version compare, before any write.
package io.github.stevdrey.dokene.integration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.customer.application.PhoneNormalizer;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class IntegrationConfigurationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-11T12:00:00Z");
    private final TenantMessagingIntegrationRepository repository = mock(TenantMessagingIntegrationRepository.class);
    private final IntegrationSecretResolver secrets = mock(IntegrationSecretResolver.class);
    private final IntegrationAuditPort audit = mock(IntegrationAuditPort.class);
    private final TenantContextProvider contexts = mock(TenantContextProvider.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final UUID tenantId = UUID.randomUUID();
    private IntegrationConfigurationService service;

    @BeforeEach
    void setUp() {
        when(contexts.requireCurrent()).thenReturn(new TenantContext(new TenantId(tenantId),
                new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()), TenantRole.OWNER));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(secrets.resolve(any())).thenReturn(Optional.of(new SecretValue("token")));
        when(repository.insert(any())).thenAnswer(call -> call.getArgument(0));
        when(repository.update(any(), any())).thenAnswer(call -> call.getArgument(1));
        service = new IntegrationConfigurationService(repository, secrets, new PhoneNormalizer(), audit, contexts,
                transactions, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void insertsAtVersionOneNormalizingTheDisplayNumberAndNamingEveryChangedField() {
        when(repository.lockByTenant(tenantId)).thenReturn(Optional.empty());

        TenantMessagingIntegration saved = service.update(new IntegrationConfigurationUpdate("100000000000000",
                "200000000000000", "+56 9 2222 0000", "CL", "env:DOKENE_WA_A", true, 0));

        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.displayPhoneNumber()).isEqualTo("+56922220000");
        assertThat(saved.secretRef()).isEqualTo(SecretRef.parse("env:DOKENE_WA_A"));
        assertThat(saved.outboundEnabled()).isFalse();
        assertThat(saved.createdAt()).isEqualTo(NOW);
        verify(repository).insert(saved);
        verify(audit).integrationUpdated(tenantId, true, EnumSet.allOf(IntegrationField.class));
        verify(secrets).resolve(SecretRef.parse("env:DOKENE_WA_A"));
    }

    @Test
    void updatesPreservingTheSwitchesAndAuditsAnUnchangedSaveWithNoFieldNames() {
        TenantMessagingIntegration current = row(true, true, 3);
        when(repository.lockByTenant(tenantId)).thenReturn(Optional.of(current));

        TenantMessagingIntegration saved = service.update(new IntegrationConfigurationUpdate("100000000000000",
                "200000000000000", "+56922220000", "CL", "env:DOKENE_WA_A", true, 3));

        assertThat(saved.version()).isEqualTo(4);
        assertThat(saved.outboundEnabled()).isTrue();
        assertThat(saved.createdAt()).isEqualTo(current.createdAt());
        assertThat(saved.updatedAt()).isEqualTo(NOW);
        verify(repository).update(current, saved);
        verify(audit).integrationUpdated(tenantId, true, EnumSet.noneOf(IntegrationField.class));
    }

    @Test
    void shapeRefusalsAreDecidedBeforeTheTransactionOpens() {
        assertRefused(update("12a", null, null, null, null, false, 0), IntegrationErrorCode.INVALID_INPUT);
        assertRefused(update(null, "1".repeat(33), null, null, null, false, 0), IntegrationErrorCode.INVALID_INPUT);
        assertRefused(update(null, null, "+56922220000", null, null, false, 0), IntegrationErrorCode.INVALID_INPUT);
        assertRefused(update(null, null, null, "CL", null, false, 0), IntegrationErrorCode.INVALID_INPUT);
        assertRefused(update(null, null, "+56912345655", "CL", null, false, 0), IntegrationErrorCode.INVALID_INPUT);
        assertRefused(update(null, null, null, null, "vault:foo", false, 99),
                IntegrationErrorCode.SECRET_REF_UNRESOLVABLE);
        assertRefused(update(null, null, null, null, "env:SPRING_DATASOURCE_PASSWORD", false, 99),
                IntegrationErrorCode.SECRET_REF_UNRESOLVABLE);
        assertRefused(update(null, null, null, null, "env:DOKENE_WA_lower", false, 99),
                IntegrationErrorCode.SECRET_REF_UNRESOLVABLE);
        verifyNoInteractions(transactions, repository, audit);
    }

    @Test
    void semanticRefusalsRunAfterTheVersionCompareAndBeforeAnyWrite() {
        when(repository.lockByTenant(tenantId)).thenReturn(Optional.of(row(false, false, 2)));

        assertRefused(update("100000000000000", null, null, null, "env:DOKENE_WA_A", true, 1),
                IntegrationErrorCode.STALE_VERSION);
        assertRefused(update("100000000000000", null, null, null, "env:DOKENE_WA_A", true, 2),
                IntegrationErrorCode.INTEGRATION_INCOMPLETE);
        when(secrets.resolve(any())).thenReturn(Optional.empty());
        assertRefused(update("100000000000000", "200000000000000", "+56922220000", "CL", "env:DOKENE_WA_NOT_DEFINED",
                true, 2), IntegrationErrorCode.SECRET_REF_UNRESOLVABLE);
        assertRefused(update(null, null, null, null, "env:DOKENE_WA_NOT_DEFINED", false, 2),
                IntegrationErrorCode.SECRET_REF_UNRESOLVABLE);
        verify(repository, never()).insert(any());
        verify(repository, never()).update(any(), any());
        verifyNoInteractions(audit);
        verify(transactions, org.mockito.Mockito.times(4)).rollback(any());
    }

    @Test
    void theKillSwitchComparesTheVersionFirstThenSkipsANoOp() {
        TenantMessagingIntegration current = row(true, false, 2);
        when(repository.lockByTenant(tenantId)).thenReturn(Optional.of(current));

        assertThatThrownBy(() -> service.setOutbound(false, 1)).isInstanceOf(IntegrationRefusedException.class)
                .extracting("code").isEqualTo(IntegrationErrorCode.STALE_VERSION);
        assertThat(service.setOutbound(false, 2)).isSameAs(current);
        verify(repository, never()).update(any(), any());
        verifyNoInteractions(audit);

        TenantMessagingIntegration saved = service.setOutbound(true, 2);
        assertThat(saved.version()).isEqualTo(3);
        assertThat(saved.outboundEnabled()).isTrue();
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.phoneNumberId()).isEqualTo(current.phoneNumberId());
        verify(repository).update(current, saved);
        verify(audit).outboundSwitchChanged(tenantId, true);
        verify(secrets, never()).resolve(any());
    }

    @Test
    void theKillSwitchInsertsARowWhenNoneExists() {
        when(repository.lockByTenant(tenantId)).thenReturn(Optional.empty());

        TenantMessagingIntegration saved = service.setOutbound(true, 0);

        assertThat(saved.version()).isEqualTo(1);
        assertThat(saved.enabled()).isFalse();
        assertThat(saved.outboundEnabled()).isTrue();
        assertThat(saved.phoneNumberId()).isNull();
        verify(repository).insert(saved);
        verify(audit).outboundSwitchChanged(tenantId, true);
        assertThat(service.setOutbound(false, 0)).isEqualTo(TenantMessagingIntegration.absent(tenantId));
    }

    @Test
    void readAnswersTheDefaultRepresentationWithoutARow() {
        when(repository.findByTenant(tenantId)).thenReturn(Optional.empty());
        assertThat(service.read()).isEqualTo(TenantMessagingIntegration.absent(tenantId));
        assertThat(service.read().version()).isZero();
        verify(audit, never()).integrationUpdated(any(), anyBoolean(), any());
    }

    private void assertRefused(IntegrationConfigurationUpdate command, IntegrationErrorCode code) {
        assertThatThrownBy(() -> service.update(command)).isInstanceOf(IntegrationRefusedException.class)
                .extracting("code").isEqualTo(code);
    }

    private static IntegrationConfigurationUpdate update(String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, String displayPhoneRegion, String secretRef, boolean enabled, long version) {
        return new IntegrationConfigurationUpdate(phoneNumberId, businessAccountId, displayPhoneNumber,
                displayPhoneRegion, secretRef, enabled, version);
    }

    private TenantMessagingIntegration row(boolean enabled, boolean outbound, long version) {
        Instant created = NOW.minusSeconds(3600);
        return new TenantMessagingIntegration(tenantId, "100000000000000", "200000000000000", "+56922220000",
                SecretRef.parse("env:DOKENE_WA_A"), enabled, outbound, false, version, created, created);
    }
}
