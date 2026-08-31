package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TenantAuthorizationServiceTest {

    private final ScopedValueTenantContextProvider tenantContextProvider = new ScopedValueTenantContextProvider();
    private final AuthorizationAuditListener auditListener = mock(AuthorizationAuditListener.class);
    private final Instant fixedInstant = Instant.parse("2026-08-31T01:00:00Z");
    private final Clock clock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

    private TenantAuthorizationService authorizationService;

    private final TenantId tenantId = TenantId.random();
    private final IdentityId identityId = new IdentityId(UUID.randomUUID());
    private final TenantMembershipId membershipId = TenantMembershipId.random();

    @BeforeEach
    void setUp() {
        authorizationService = new DefaultTenantAuthorizationService(tenantContextProvider, auditListener, clock);
    }

    @Test
    void allowsActionWhenRolePossessesPermissionInActiveContext() {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.ADMIN);

        tenantContextProvider.runWithContext(context, () -> {
            AuthorizationDecision decision = authorizationService.evaluate(TenantPermission.CUSTOMER_WRITE);

            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.rejectionReason()).isEmpty();
            assertThat(authorizationService.hasPermission(TenantPermission.CUSTOMER_WRITE)).isTrue();
        });
    }

    @Test
    void deniesActionWhenRoleLacksPermission() {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.VIEWER);

        tenantContextProvider.runWithContext(context, () -> {
            AuthorizationDecision decision = authorizationService.evaluate(TenantPermission.CUSTOMER_WRITE);

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.rejectionReason()).hasValue("Role VIEWER lacks permission CUSTOMER_WRITE");
            assertThat(authorizationService.hasPermission(TenantPermission.CUSTOMER_WRITE)).isFalse();
        });
    }

    @Test
    void failsClosedWhenNoTenantContextIsActive() {
        assertThat(tenantContextProvider.current()).isEmpty();

        AuthorizationDecision decision = authorizationService.evaluate(TenantPermission.CUSTOMER_READ);

        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.rejectionReason()).hasValue("No active tenant context");
        assertThat(authorizationService.hasPermission(TenantPermission.CUSTOMER_READ)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = TenantMembershipStatus.class, names = {"INVITED", "SUSPENDED", "REVOKED"})
    void deniesAccessWhenMembershipStatusIsNotActive(TenantMembershipStatus status) {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.OWNER, status);

        tenantContextProvider.runWithContext(context, () -> {
            AuthorizationDecision decision = authorizationService.evaluate(TenantPermission.CUSTOMER_READ);

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.rejectionReason()).hasValueSatisfying(reason ->
                    assertThat(reason).contains("Tenant membership is not active")
            );
            assertThat(authorizationService.hasPermission(TenantPermission.CUSTOMER_READ)).isFalse();
        });
    }

    @Test
    void deniesAccessWhenTargetResourceBelongsToDifferentTenant() {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.OWNER);
        TenantId foreignTenantId = TenantId.random();
        TenantScopedResource foreignResource = () -> foreignTenantId;

        tenantContextProvider.runWithContext(context, () -> {
            AuthorizationDecision decision = authorizationService.evaluate(context, TenantPermission.CUSTOMER_READ, foreignResource);

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.rejectionReason()).hasValue("Resource tenant does not match active tenant context");
            assertThat(authorizationService.hasResourceAccess(TenantPermission.CUSTOMER_READ, foreignResource)).isFalse();
        });
    }

    @Test
    void allowsAccessWhenTargetResourceBelongsToSameTenant() {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.OPERATOR);
        TenantScopedResource ownResource = () -> tenantId;

        tenantContextProvider.runWithContext(context, () -> {
            AuthorizationDecision decision = authorizationService.evaluate(context, TenantPermission.CUSTOMER_WRITE, ownResource);

            assertThat(decision.isAllowed()).isTrue();
            assertThat(authorizationService.hasResourceAccess(TenantPermission.CUSTOMER_WRITE, ownResource)).isTrue();
        });
    }

    @Test
    void requirePermissionSucceedsWhenAllowed() {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.OPERATOR);

        tenantContextProvider.runWithContext(context, () -> {
            authorizationService.requirePermission(TenantPermission.MESSAGE_DRAFT);
            verify(auditListener, never()).onAuthorizationDenied(any());
        });
    }

    @Test
    void requirePermissionThrowsAndEmitsAuditEventWhenDenied() {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.VIEWER);
        AtomicReference<AuthorizationDeniedEvent> capturedEvent = new AtomicReference<>();

        tenantContextProvider.runWithContext(context, () -> {
            assertThatThrownBy(() -> authorizationService.requirePermission(TenantPermission.MESSAGE_APPROVE))
                    .isInstanceOf(TenantAccessDeniedException.class)
                    .hasMessage("Access denied");

            verify(auditListener).onAuthorizationDenied(any(AuthorizationDeniedEvent.class));
        });
    }

    @Test
    void requireResourceAccessThrowsAndEmitsAuditEventWhenResourceTenantMismatches() {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.OWNER);
        TenantId otherTenantId = TenantId.random();

        tenantContextProvider.runWithContext(context, () -> {
            assertThatThrownBy(() -> authorizationService.requireResourceAccess(TenantPermission.CUSTOMER_READ, otherTenantId))
                    .isInstanceOf(TenantAccessDeniedException.class)
                    .hasMessage("Access denied");

            verify(auditListener).onAuthorizationDenied(any(AuthorizationDeniedEvent.class));
        });
    }

    @Test
    void exceptionFromAuditListenerDoesNotSuppressAccessDeniedException() {
        TenantContext context = new TenantContext(tenantId, identityId, membershipId, TenantRole.VIEWER);
        doThrow(new RuntimeException("audit failure")).when(auditListener).onAuthorizationDenied(any());

        tenantContextProvider.runWithContext(context, () -> {
            assertThatThrownBy(() -> authorizationService.requirePermission(TenantPermission.CUSTOMER_WRITE))
                    .isInstanceOf(TenantAccessDeniedException.class)
                    .hasMessage("Access denied");
        });
    }

    @Test
    void evaluatesExplicitContextWithoutAmbientContext() {
        TenantContext explicitContext = new TenantContext(tenantId, identityId, membershipId, TenantRole.OPERATOR);

        AuthorizationDecision allowedDecision = authorizationService.evaluate(explicitContext, TenantPermission.CUSTOMER_READ);
        assertThat(allowedDecision.isAllowed()).isTrue();

        AuthorizationDecision deniedDecision = authorizationService.evaluate(explicitContext, TenantPermission.TENANT_UPDATE);
        assertThat(deniedDecision.isAllowed()).isFalse();
    }
}
