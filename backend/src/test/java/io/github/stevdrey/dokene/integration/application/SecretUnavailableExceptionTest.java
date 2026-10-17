// ABOUTME: Pins the closed category set and the fixed, value free message of SecretUnavailableException (spec 0006).
// ABOUTME: The message carries only category, scope kind and tenant id, never the reference or the secret.
package io.github.stevdrey.dokene.integration.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.ScopeKind;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import org.junit.jupiter.api.Test;

class SecretUnavailableExceptionTest {
    @Test
    void theCategorySetIsClosed() {
        assertThat(Category.values()).containsExactly(Category.MISSING, Category.WRONG_PURPOSE,
                Category.SOURCE_DISABLED);
        assertThat(ScopeKind.values()).containsExactly(ScopeKind.TENANT, ScopeKind.DEPLOYMENT);
    }

    @Test
    void aTenantDenialNamesTheCategoryScopeAndTenantOnly() {
        TenantId tenantId = TenantId.random();

        var denial = new SecretUnavailableException(Category.MISSING,
                new Tenant(tenantId, SecretPurpose.WHATSAPP_ACCESS_TOKEN));

        assertThat(denial.category()).isEqualTo(Category.MISSING);
        assertThat(denial.scopeKind()).isEqualTo(ScopeKind.TENANT);
        assertThat(denial.tenantId()).contains(tenantId);
        assertThat(denial.tenantField()).isEqualTo(tenantId.value().toString());
        assertThat(denial.getCause()).isNull();
        assertThat(denial.getMessage())
                .isEqualTo("Secret unavailable: category=MISSING scope=TENANT tenant=" + tenantId.value());
        assertThat(denial).hasToString(SecretUnavailableException.class.getName() + ": " + denial.getMessage());
    }

    @Test
    void aDeploymentDenialHasNoTenant() {
        var denial = new SecretUnavailableException(Category.WRONG_PURPOSE,
                new Deployment(SecretPurpose.APP_SECRET));

        assertThat(denial.scopeKind()).isEqualTo(ScopeKind.DEPLOYMENT);
        assertThat(denial.tenantId()).isEmpty();
        assertThat(denial.tenantField()).isEqualTo("none");
        assertThat(denial.getMessage())
                .isEqualTo("Secret unavailable: category=WRONG_PURPOSE scope=DEPLOYMENT tenant=none");
    }
}
