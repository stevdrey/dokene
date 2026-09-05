package io.github.stevdrey.dokene.audit.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.application.AuthorizationDeniedEvent;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DurableAuthorizationAuditListenerTest {
    @ParameterizedTest
    @CsvSource({
            "Tenant membership is not active (status: INVITED), INACTIVE_MEMBERSHIP",
            "Tenant membership is not active (status: SUSPENDED), INACTIVE_MEMBERSHIP",
            "Tenant membership is not active (status: REVOKED), INACTIVE_MEMBERSHIP",
            "Role VIEWER lacks permission AUDIT_READ, INSUFFICIENT_PERMISSION",
            "Role OPERATOR lacks permission MEMBERSHIP_ROLE_UPDATE, INSUFFICIENT_PERMISSION",
            "Role ADMIN lacks permission TENANT_ARCHIVE, INSUFFICIENT_PERMISSION",
            "Tenant membership is not active (status: UNKNOWN), UNSPECIFIED",
            "Tenant membership is not active (status: REVOKED) secret, UNSPECIFIED",
            "Role UNKNOWN lacks permission AUDIT_READ, UNSPECIFIED",
            "Role VIEWER lacks permission UNKNOWN, UNSPECIFIED",
            "Role VIEWER lacks permission AUDIT_READ secret, UNSPECIFIED",
            "Role VIEWER lacks permission, UNSPECIFIED"
    })
    void recognizesOnlyExactKnownDynamicReasons(String reason, AuditDenialReason expected) {
        AuditRecorder recorder = mock(AuditRecorder.class);
        var listener = new DurableAuthorizationAuditListener(recorder);
        listener.onAuthorizationDenied(new AuthorizationDeniedEvent(Instant.now(), null, null, null, null,
                TenantPermission.AUDIT_READ, null, reason));
        verify(recorder).authorizationDenied(TenantPermission.AUDIT_READ, expected);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void neverForwardsRawReasonForeignTenantOrSuppliedAttribution() {
        AuditRecorder recorder = mock(AuditRecorder.class);
        var listener = new DurableAuthorizationAuditListener(recorder);
        listener.onAuthorizationDenied(new AuthorizationDeniedEvent(Instant.now(), new IdentityId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()), TenantRole.VIEWER,
                TenantPermission.AUDIT_READ, new TenantId(UUID.randomUUID()), "Bearer secret / message body / credential"));
        verify(recorder).authorizationDenied(TenantPermission.AUDIT_READ, AuditDenialReason.UNSPECIFIED);
        verifyNoMoreInteractions(recorder);
    }
}
