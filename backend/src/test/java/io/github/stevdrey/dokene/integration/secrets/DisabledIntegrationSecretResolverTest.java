// ABOUTME: Proves the disabled resolver refuses every call with SOURCE_DISABLED and warns only once (spec 0006 AC-6).
// ABOUTME: Many denials across tenants and the deployment scope produce one WARN line and no leaked reference.
package io.github.stevdrey.dokene.integration.secrets;

import static io.github.stevdrey.dokene.integration.testsupport.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.ScopeKind;
import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DisabledIntegrationSecretResolverTest {
    @Test
    void refusesEveryCallWithSourceDisabledAndWarnsOnce() {
        var resolver = new DisabledIntegrationSecretResolver();
        TenantId tenantId = TenantId.random();
        String ref = SecretCanaries.ref();
        var reference = new SecretReference(ref);

        try (var logs = new LogCapture()) {
            SecretUnavailableException first = catchThrowableOfType(SecretUnavailableException.class,
                    () -> resolver.resolve(new Tenant(tenantId, SecretPurpose.WHATSAPP_ACCESS_TOKEN), reference));
            for (int i = 0; i < 20; i++) {
                catchThrowableOfType(SecretUnavailableException.class,
                        () -> resolver.resolve(new Tenant(TenantId.random(), SecretPurpose.WHATSAPP_ACCESS_TOKEN),
                                reference));
            }
            SecretUnavailableException deployment = catchThrowableOfType(SecretUnavailableException.class,
                    () -> resolver.resolve(new Deployment(SecretPurpose.APP_SECRET), reference));

            assertThat(first.category()).isEqualTo(Category.SOURCE_DISABLED);
            assertThat(first.scopeKind()).isEqualTo(ScopeKind.TENANT);
            assertThat(first.tenantId()).contains(tenantId);
            assertThat(deployment.category()).isEqualTo(Category.SOURCE_DISABLED);
            assertThat(deployment.scopeKind()).isEqualTo(ScopeKind.DEPLOYMENT);

            var warnings = logs.warningsFrom(DisabledIntegrationSecretResolver.class);
            assertThat(warnings).hasSize(1);
            assertThat(warnings.getFirst().getFormattedMessage()).isEqualTo(
                    "Secret unavailable category=SOURCE_DISABLED scope=TENANT tenant=" + tenantId.value());
            assertThat(containsAny(logs.text(), List.of(ref))).isFalse();
            assertThat(containsAny(first.getMessage() + first, List.of(ref))).isFalse();
        }
    }
}
