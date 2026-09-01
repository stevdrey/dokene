package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.tenant.application.AuthorizationAuditListener;
import io.github.stevdrey.dokene.tenant.application.AuthorizationDeniedEvent;
import io.github.stevdrey.dokene.tenant.application.ScopedValueTenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@SpringBootTest(classes = {
        TenantAuthorizationConfiguration.class,
        TenantPermissionEvaluator.class,
        TenantSecurityExpressions.class,
        io.github.stevdrey.dokene.tenant.application.DefaultTenantAuthorizationService.class,
        TenantMethodSecurityTest.TestSecurityConfig.class
})
class TenantMethodSecurityTest {

    @TestConfiguration
    static class TestSecurityConfig {

        @Bean
        TenantContextProvider tenantContextProvider() {
            return new ScopedValueTenantContextProvider();
        }

        @Bean
        @Primary
        AuthorizationAuditListener testAuthorizationAuditListener() {
            return mock(AuthorizationAuditListener.class);
        }

        @Bean
        ProtectedSampleService protectedSampleService() {
            return new ProtectedSampleService();
        }
    }

    @Service
    static class ProtectedSampleService {

        @PreAuthorize("@tenantAuth.hasPermission('CUSTOMER_READ')")
        public String readCustomerData() {
            return "customer-data";
        }

        @PreAuthorize("@tenantAuth.hasPermission('CUSTOMER_WRITE')")
        public void updateCustomerData() {
        }

        @PreAuthorize("@tenantAuth.hasPermission('TENANT_ARCHIVE')")
        public void archiveTenant() {
        }

        @PreAuthorize("@tenantAuth.hasResourceAccess(#resource, 'CUSTOMER_WRITE')")
        public void updateCustomer(TenantScopedResource resource) {
        }

        @PreAuthorize("hasPermission(null, 'MESSAGE_APPROVE')")
        public void approveMessage() {
        }

        @PreAuthorize("hasPermission(#resource, 'MESSAGE_SEND')")
        public void sendMessage(TenantScopedResource resource) {
        }
    }

    @Autowired
    private ProtectedSampleService sampleService;

    @Autowired
    private TenantContextProvider tenantContextProvider;

    @Autowired
    private AuthorizationAuditListener auditListener;

    private final TenantId tenantId = TenantId.random();
    private final IdentityId identityId = new IdentityId(UUID.randomUUID());
    private final TenantMembershipId membershipId = TenantMembershipId.random();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        clearInvocations(auditListener);
    }

    @Test
    void allowsOwnerToPerformAllProtectedOperations() {
        authenticate(identityId);
        TenantContext ownerContext = new TenantContext(tenantId, identityId, membershipId, TenantRole.OWNER);

        tenantContextProvider.runWithContext(ownerContext, () -> {
            assertThat(sampleService.readCustomerData()).isEqualTo("customer-data");
            sampleService.updateCustomerData();
            sampleService.archiveTenant();
            sampleService.approveMessage();
            sampleService.sendMessage(() -> tenantId);
        });
    }

    @Test
    void allowsAdminToPerformOperationalAndAdministrativeTasksExceptArchive() {
        authenticate(identityId);
        TenantContext adminContext = new TenantContext(tenantId, identityId, membershipId, TenantRole.ADMIN);

        tenantContextProvider.runWithContext(adminContext, () -> {
            assertThat(sampleService.readCustomerData()).isEqualTo("customer-data");
            sampleService.updateCustomerData();
            sampleService.approveMessage();

            assertThatThrownBy(() -> sampleService.archiveTenant())
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    void operatorCanPerformOperationalActionsButNotAdministrativeOrArchival() {
        authenticate(identityId);
        TenantContext operatorContext = new TenantContext(tenantId, identityId, membershipId, TenantRole.OPERATOR);

        tenantContextProvider.runWithContext(operatorContext, () -> {
            assertThat(sampleService.readCustomerData()).isEqualTo("customer-data");
            sampleService.updateCustomerData();
            sampleService.approveMessage();

            assertThatThrownBy(() -> sampleService.archiveTenant())
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    void viewerCanOnlyRead() {
        authenticate(identityId);
        TenantContext viewerContext = new TenantContext(tenantId, identityId, membershipId, TenantRole.VIEWER);

        tenantContextProvider.runWithContext(viewerContext, () -> {
            assertThat(sampleService.readCustomerData()).isEqualTo("customer-data");

            assertThatThrownBy(() -> sampleService.updateCustomerData())
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> sampleService.approveMessage())
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> sampleService.archiveTenant())
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    void rejectsResourceAccessWhenResourceBelongsToAnotherTenant() {
        authenticate(identityId);
        TenantContext ownerContext = new TenantContext(tenantId, identityId, membershipId, TenantRole.OWNER);
        TenantScopedResource foreignResource = TenantId::random;

        tenantContextProvider.runWithContext(ownerContext, () -> {
            assertThatThrownBy(() -> sampleService.updateCustomer(foreignResource))
                    .isInstanceOf(AccessDeniedException.class);

            assertThatThrownBy(() -> sampleService.sendMessage(foreignResource))
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    void failsClosedWhenNoTenantContextIsPresent() {
        authenticate(identityId);

        assertThat(tenantContextProvider.current()).isEmpty();
        assertThatThrownBy(() -> sampleService.readCustomerData())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void failsClosedWhenMembershipIsSuspended() {
        authenticate(identityId);
        TenantContext suspendedContext = new TenantContext(
                tenantId, identityId, membershipId, TenantRole.OWNER, TenantMembershipStatus.SUSPENDED
        );

        tenantContextProvider.runWithContext(suspendedContext, () -> {
            assertThatThrownBy(() -> sampleService.readCustomerData())
                    .isInstanceOf(AccessDeniedException.class);
        });
    }

    @Test
    void auditsDeniedMethodSecurityChecks() {
        authenticate(identityId);
        TenantContext viewerContext = new TenantContext(tenantId, identityId, membershipId, TenantRole.VIEWER);

        tenantContextProvider.runWithContext(viewerContext, () -> {
            assertThatThrownBy(() -> sampleService.updateCustomerData())
                    .isInstanceOf(AccessDeniedException.class);

            verify(auditListener).onAuthorizationDenied(any(AuthorizationDeniedEvent.class));
        });
    }

    @Test
    void auditsMissingResourceOwnershipEvidenceFromSpel() {
        authenticate(identityId);
        TenantContext ownerContext = new TenantContext(tenantId, identityId, membershipId, TenantRole.OWNER);

        tenantContextProvider.runWithContext(ownerContext, () -> {
            assertThatThrownBy(() -> sampleService.updateCustomer(null))
                    .isInstanceOf(AccessDeniedException.class);

            verify(auditListener).onAuthorizationDenied(any(AuthorizationDeniedEvent.class));
        });
    }

    private void authenticate(IdentityId authenticatedIdentityId) {
        AuthenticatedTenantIdentity principal = () -> authenticatedIdentityId;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "not-used", java.util.List.of())
        );
    }
}
