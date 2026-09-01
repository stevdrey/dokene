package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantPermissionEvaluatorTest {

    private final TenantAuthorizationService authorizationService = mock(TenantAuthorizationService.class);
    private final TenantPermissionEvaluator evaluator = new TenantPermissionEvaluator(authorizationService);

    @Test
    void auditsMissingInvalidAndUnsupportedGlobalPermissionValues() {
        assertThat(evaluator.hasPermission(null, null, null)).isFalse();
        assertThat(evaluator.hasPermission(null, null, "INVALID_PERMISSION")).isFalse();
        assertThat(evaluator.hasPermission(null, null, 42)).isFalse();

        verify(authorizationService, times(3)).hasPermission((TenantPermission) null);
    }

    @Test
    void preservesKnownResourcesWhenPermissionValueIsInvalid() {
        TenantScopedResource resource = TenantId::random;
        TenantId resourceTenantId = TenantId.random();

        assertThat(evaluator.hasPermission(null, resource, "INVALID_PERMISSION")).isFalse();
        assertThat(evaluator.hasPermission(null, resourceTenantId, "INVALID_PERMISSION")).isFalse();

        verify(authorizationService).hasResourceAccess((TenantPermission) null, resource);
        verify(authorizationService).hasResourceAccess((TenantPermission) null, resourceTenantId);
    }

    @Test
    void auditsUnsupportedTargetDomainObjectWithKnownPermission() {
        assertThat(evaluator.hasPermission(null, new Object(), TenantPermission.CUSTOMER_READ)).isFalse();

        verify(authorizationService).hasResourceAccess(TenantPermission.CUSTOMER_READ, (TenantId) null);
    }

    @Test
    void preservesTenantIdWhenIdentifierOverloadHasInvalidPermission() {
        UUID tenantUuid = UUID.randomUUID();

        assertThat(evaluator.hasPermission(null, tenantUuid, "Tenant", "INVALID_PERMISSION")).isFalse();

        verify(authorizationService).hasResourceAccess((TenantPermission) null, new TenantId(tenantUuid));
    }

    @Test
    void auditsUnsupportedIdentifierTargetsWithKnownPermission() {
        assertThat(evaluator.hasPermission(null, null, "Tenant", TenantPermission.CUSTOMER_READ)).isFalse();
        assertThat(evaluator.hasPermission(null, UUID.randomUUID(), "Customer", TenantPermission.CUSTOMER_READ)).isFalse();

        verify(authorizationService, times(2)).hasResourceAccess(TenantPermission.CUSTOMER_READ, (TenantId) null);
    }
}
