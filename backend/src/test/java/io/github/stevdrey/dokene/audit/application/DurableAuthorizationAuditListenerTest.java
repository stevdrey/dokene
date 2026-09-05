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

class DurableAuthorizationAuditListenerTest {
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
