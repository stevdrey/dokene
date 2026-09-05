package io.github.stevdrey.dokene.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AuditEventTest {
    @ParameterizedTest
    @EnumSource(AuditDenialReason.class)
    void noTenantContextReasonIsExclusiveToGlobalDenials(AuditDenialReason reason) {
        Runnable global = () -> new AuditEvent(UUID.randomUUID(), Instant.now(), null, null, null,
                AuditEventType.AUTHORIZATION_DENIED, null, AuditOutcome.DENIED, UUID.randomUUID(),
                new AuditMetadata.AuthorizationDenied(null, reason));
        Runnable attributed = () -> new AuditEvent(UUID.randomUUID(), Instant.now(), new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()),
                AuditEventType.AUTHORIZATION_DENIED, null, AuditOutcome.DENIED, UUID.randomUUID(),
                new AuditMetadata.AuthorizationDenied(null, reason));
        if (reason == AuditDenialReason.NO_TENANT_CONTEXT) {
            assertThatCode(global::run).doesNotThrowAnyException();
            assertThatThrownBy(attributed::run).isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThatCode(attributed::run).doesNotThrowAnyException();
            assertThatThrownBy(global::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void normalizesTimestampToDatabasePrecisionAndRequiresCorrelation() {
        AuditEvent event = denial(Instant.parse("2026-09-01T01:02:03.123456789Z"), UUID.randomUUID(), null, null, null);
        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-09-01T01:02:03.123456Z"));
        assertThatThrownBy(() -> denial(Instant.now(), null, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsPartialAttributionAndInvalidEventShapes() {
        assertThatThrownBy(() -> denial(Instant.now(), UUID.randomUUID(), new TenantId(UUID.randomUUID()), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), null, null, null,
                AuditEventType.MEMBERSHIP_ROLE_CHANGED, new AuditTarget(AuditTarget.Type.MEMBERSHIP, UUID.randomUUID()),
                AuditOutcome.SUCCESS, UUID.randomUUID(), new AuditMetadata.MembershipRoleChanged(TenantRole.VIEWER, TenantRole.ADMIN)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditEvent(UUID.randomUUID(), Instant.now(), null, null, null,
                AuditEventType.AUTHORIZATION_DENIED, null, AuditOutcome.DENIED, UUID.randomUUID(),
                new AuditMetadata.AuthorizationDenied(null, AuditDenialReason.CROSS_TENANT_RESOURCE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditMetadata.MembershipRoleChanged(TenantRole.OWNER, TenantRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditMetadata.MembershipRoleChanged(TenantRole.ADMIN, TenantRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AuditEvent denial(Instant timestamp, UUID correlation, TenantId tenant, IdentityId actor, TenantMembershipId membership) {
        return new AuditEvent(UUID.randomUUID(), timestamp, tenant, actor, membership, AuditEventType.AUTHORIZATION_DENIED,
                null, AuditOutcome.DENIED, correlation, new AuditMetadata.AuthorizationDenied(null, AuditDenialReason.NO_TENANT_CONTEXT));
    }
}
