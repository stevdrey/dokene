package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantScopedResource;
import org.junit.jupiter.api.Test;

class TenantSecurityExpressionsTest {

    private final TenantAuthorizationService authorizationService = mock(TenantAuthorizationService.class);
    private final TenantSecurityExpressions expressions = new TenantSecurityExpressions(authorizationService);

    @Test
    void auditsMissingAndInvalidGlobalPermissionNames() {
        assertThat(expressions.hasPermission((String) null)).isFalse();
        assertThat(expressions.hasPermission("INVALID_PERMISSION")).isFalse();

        verify(authorizationService, times(2)).hasPermission((TenantPermission) null);
    }

    @Test
    void auditsMissingAndInvalidResourcePermissionNames() {
        TenantScopedResource resource = TenantId::random;

        assertThat(expressions.hasResourceAccess(resource, (String) null)).isFalse();
        assertThat(expressions.hasResourceAccess(resource, "INVALID_PERMISSION")).isFalse();

        verify(authorizationService, times(2)).hasResourceAccess((TenantPermission) null, resource);
    }

    @Test
    void auditsMissingAndInvalidResourceTenantPermissionNames() {
        TenantId resourceTenantId = TenantId.random();

        assertThat(expressions.hasResourceAccess(resourceTenantId, (String) null)).isFalse();
        assertThat(expressions.hasResourceAccess(resourceTenantId, "INVALID_PERMISSION")).isFalse();

        verify(authorizationService, times(2)).hasResourceAccess((TenantPermission) null, resourceTenantId);
    }
}
