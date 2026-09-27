package io.github.stevdrey.dokene.audit.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.application.ActionGateRejectionReason;
import io.github.stevdrey.dokene.followup.application.AiActionGateAuditListener.SecurityRejectionEvent;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DurableAiActionGateAuditListenerTest {

    @ParameterizedTest
    @CsvSource({
            "NO_TENANT_CONTEXT, NO_TENANT_CONTEXT",
            "UNAUTHORIZED, INSUFFICIENT_PERMISSION",
            "CUSTOMER_NOT_FOUND, CROSS_TENANT_RESOURCE",
            "CUSTOMER_ARCHIVED, INSUFFICIENT_PERMISSION",
            "DO_NOT_CONTACT, INSUFFICIENT_PERMISSION",
            "NO_CONTACT_CONSENT, INSUFFICIENT_PERMISSION",
            "FOLLOW_UP_INELIGIBLE, INSUFFICIENT_PERMISSION",
            "STALE_STATE, INSUFFICIENT_PERMISSION",
            "DISALLOWED_ACTION, INSUFFICIENT_PERMISSION",
            "DISALLOWED_TEMPLATE_INTENT, INSUFFICIENT_PERMISSION",
            "INVALID_RECOMMENDATION, INSUFFICIENT_PERMISSION"
    })
    void mapsAllGateRejectionReasonsToDurableAuditDenialReasons(ActionGateRejectionReason reason, AuditDenialReason expected) {
        AuditRecorder recorder = mock(AuditRecorder.class);
        DurableAiActionGateAuditListener listener = new DurableAiActionGateAuditListener(recorder);

        SecurityRejectionEvent event = new SecurityRejectionEvent(
                new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()),
                new CustomerId(UUID.randomUUID()),
                reason,
                "DIAGNOSTIC_CODE",
                Instant.now());

        listener.onSecurityRejection(event);

        verify(recorder).authorizationDenied(TenantPermission.FOLLOWUP_EVALUATE, expected);
        verifyNoMoreInteractions(recorder);
    }

    @ParameterizedTest
    @CsvSource({
            "Role OPERATOR lacks permission FOLLOWUP_EVALUATE, INSUFFICIENT_PERMISSION",
            "Role VIEWER lacks permission FOLLOWUP_EVALUATE, INSUFFICIENT_PERMISSION",
            "Tenant membership is not active (status: SUSPENDED), INACTIVE_MEMBERSHIP",
            "Tenant membership is not active (status: INVITED), INACTIVE_MEMBERSHIP",
            "Requested permission is required, MISSING_PERMISSION",
            "Tenant context has no role assigned, MISSING_ROLE",
            "Resource tenant does not match active tenant context, CROSS_TENANT_RESOURCE",
            "Resource tenant ID is required, MISSING_RESOURCE_TENANT"
    })
    void mapsUnauthorizedDiagnosticCodesToDurableAuditTaxonomy(String diagnosticCode, AuditDenialReason expected) {
        AuditRecorder recorder = mock(AuditRecorder.class);
        DurableAiActionGateAuditListener listener = new DurableAiActionGateAuditListener(recorder);

        SecurityRejectionEvent event = new SecurityRejectionEvent(
                new TenantId(UUID.randomUUID()),
                new IdentityId(UUID.randomUUID()),
                new CustomerId(UUID.randomUUID()),
                ActionGateRejectionReason.UNAUTHORIZED,
                diagnosticCode,
                Instant.now());

        listener.onSecurityRejection(event);

        verify(recorder).authorizationDenied(TenantPermission.FOLLOWUP_EVALUATE, expected);
        verifyNoMoreInteractions(recorder);
    }

    @Test
    void requiresNonNullEventAndRecorder() {
        assertThatThrownBy(() -> new DurableAiActionGateAuditListener(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Audit recorder is required");

        AuditRecorder recorder = mock(AuditRecorder.class);
        DurableAiActionGateAuditListener listener = new DurableAiActionGateAuditListener(recorder);
        assertThatThrownBy(() -> listener.onSecurityRejection(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Security rejection event is required");
    }
}
