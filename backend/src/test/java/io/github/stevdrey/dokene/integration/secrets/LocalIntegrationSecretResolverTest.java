// ABOUTME: Proves the local resolver confines every key to its scope, copies on resolve and warns without leaking.
// ABOUTME: Spec 0006 AC-4, AC-5 and AC-6 with random canaries and a boolean log check that never prints them.
package io.github.stevdrey.dokene.integration.secrets;

import static io.github.stevdrey.dokene.integration.secrets.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretScope;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.ScopeKind;
import io.github.stevdrey.dokene.integration.secrets.LocalIntegrationSecretResolver.ConfiguredEntry;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalIntegrationSecretResolverTest {
    private final TenantId tenantA = TenantId.random();
    private final TenantId tenantB = TenantId.random();
    private final SecretReference tokenRef = new SecretReference(SecretCanaries.ref());
    private final SecretReference sharedRef = new SecretReference(SecretCanaries.ref());
    private final SecretReference verifyRef = new SecretReference(SecretCanaries.ref());
    private final SecretReference appSecretRef = new SecretReference(SecretCanaries.ref());
    private final String tokenA = SecretCanaries.value();
    private final String sharedA = SecretCanaries.value();
    private final String sharedB = SecretCanaries.value();
    private final String sharedDeployment = SecretCanaries.value();
    private final String verifyToken = SecretCanaries.value();
    private final String appSecret = SecretCanaries.value();
    private final List<String> canaries = List.of(tokenA, sharedA, sharedB, sharedDeployment, verifyToken, appSecret,
            tokenRef.value(), sharedRef.value(), verifyRef.value(), appSecretRef.value());

    private final Tenant scopeA = new Tenant(tenantA, SecretPurpose.WHATSAPP_ACCESS_TOKEN);
    private final Tenant scopeB = new Tenant(tenantB, SecretPurpose.WHATSAPP_ACCESS_TOKEN);
    private final Deployment verifyScope = new Deployment(SecretPurpose.WEBHOOK_VERIFY_TOKEN);
    private final Deployment appSecretScope = new Deployment(SecretPurpose.APP_SECRET);

    private LocalIntegrationSecretResolver resolver;
    private LogCapture logs;

    @BeforeEach
    void setUp() {
        resolver = new LocalIntegrationSecretResolver(List.of(
                tenant(tenantA, tokenRef, tokenA),
                tenant(tenantA, sharedRef, sharedA),
                tenant(tenantB, sharedRef, sharedB),
                deployment(sharedRef, SecretPurpose.WEBHOOK_VERIFY_TOKEN, sharedDeployment),
                deployment(verifyRef, SecretPurpose.WEBHOOK_VERIFY_TOKEN, verifyToken),
                deployment(appSecretRef, SecretPurpose.APP_SECRET, appSecret)));
        logs = new LogCapture();
    }

    @AfterEach
    void tearDown() {
        boolean leaked = containsAny(logs.text(), canaries);
        logs.close();
        assertThat(leaked).isFalse();
    }

    @Test
    void resolvesATenantTokenAndADeploymentSecretAsFreshCopies() {
        try (ResolvedSecret first = resolver.resolve(scopeA, tokenRef);
                ResolvedSecret second = resolver.resolve(verifyScope, verifyRef)) {
            assertThat(first).isNotSameAs(second);
            assertThat(first.<String>withValue(String::new)).isEqualTo(tokenA);
            assertThat(second.<String>withValue(String::new)).isEqualTo(verifyToken);
        }
        assertThat(logs.events()).isEmpty();
    }

    /** ResolvedSecret wipes its argument; the resolver must therefore hand out a copy each time. */
    @Test
    void aSecondResolveStillYieldsTheValueAfterTheFirstResultIsClosed() {
        try (ResolvedSecret first = resolver.resolve(scopeA, tokenRef)) {
            assertThat(first.<String>withValue(String::new)).isEqualTo(tokenA);
        }
        try (ResolvedSecret second = resolver.resolve(scopeA, tokenRef)) {
            assertThat(second.<String>withValue(String::new)).isEqualTo(tokenA);
        }
    }

    @Test
    void aTenantKeyIsMissingForAnotherTenantTheDeploymentAndUnknownKeys() {
        assertDenied(scopeB, tokenRef, Category.MISSING, ScopeKind.TENANT, tenantB);
        assertDenied(verifyScope, tokenRef, Category.MISSING, ScopeKind.DEPLOYMENT, null);
        assertDenied(scopeA, new SecretReference("never-configured"), Category.MISSING, ScopeKind.TENANT, tenantA);
        assertDenied(verifyScope, new SecretReference("never-configured"), Category.MISSING, ScopeKind.DEPLOYMENT,
                null);
    }

    @Test
    void aDeploymentKeyIsMissingForEveryTenant() {
        assertDenied(scopeA, verifyRef, Category.MISSING, ScopeKind.TENANT, tenantA);
        assertDenied(scopeB, appSecretRef, Category.MISSING, ScopeKind.TENANT, tenantB);
    }

    @Test
    void theSameBareKeyResolvesToEachOwnersOwnValue() {
        try (ResolvedSecret a = resolver.resolve(scopeA, sharedRef);
                ResolvedSecret b = resolver.resolve(scopeB, sharedRef);
                ResolvedSecret d = resolver.resolve(verifyScope, sharedRef)) {
            assertThat(a.<String>withValue(String::new)).isEqualTo(sharedA);
            assertThat(b.<String>withValue(String::new)).isEqualTo(sharedB);
            assertThat(d.<String>withValue(String::new)).isEqualTo(sharedDeployment);
        }
    }

    @Test
    void aDeploymentKeyAskedForWithTheWrongPurposeIsWrongPurpose() {
        assertDenied(verifyScope, appSecretRef, Category.WRONG_PURPOSE, ScopeKind.DEPLOYMENT, null);
        assertDenied(appSecretScope, verifyRef, Category.WRONG_PURPOSE, ScopeKind.DEPLOYMENT, null);
    }

    @Test
    void readsNothingButTheEntryList() {
        var empty = new LocalIntegrationSecretResolver(List.of());

        assertThatThrownBy(() -> empty.resolve(scopeA, tokenRef)).isInstanceOf(SecretUnavailableException.class);
        assertThatThrownBy(() -> empty.resolve(verifyScope, verifyRef))
                .isInstanceOf(SecretUnavailableException.class);
    }

    @Test
    void rejectsAnEntryWhoseOwnerAndTenantIdDisagree() {
        assertThatThrownBy(() -> new ConfiguredEntry(ScopeKind.TENANT, null, tokenRef,
                SecretPurpose.WHATSAPP_ACCESS_TOKEN, tokenA)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConfiguredEntry(ScopeKind.DEPLOYMENT, tenantA, verifyRef,
                SecretPurpose.WEBHOOK_VERIFY_TOKEN, verifyToken)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tenant(tenantA, tokenRef, tokenA)).hasToString("ConfiguredEntry[REDACTED]");
    }

    private void assertDenied(SecretScope scope, SecretReference reference, Category category, ScopeKind kind,
            TenantId tenantId) {
        int before = logs.warningsFrom(LocalIntegrationSecretResolver.class).size();

        SecretUnavailableException denial = catchThrowableOfType(SecretUnavailableException.class,
                () -> resolver.resolve(scope, reference));

        assertThat(denial.category()).isEqualTo(category);
        assertThat(denial.scopeKind()).isEqualTo(kind);
        assertThat(denial.tenantId().orElse(null)).isEqualTo(tenantId);
        assertThat(containsAny(denial.getMessage(), canaries)).isFalse();
        assertThat(containsAny(denial.toString(), canaries)).isFalse();
        var warnings = logs.warningsFrom(LocalIntegrationSecretResolver.class);
        assertThat(warnings).hasSize(before + 1);
        assertThat(warnings.getLast().getFormattedMessage()).isEqualTo("Secret unavailable category=" + category
                + " scope=" + kind + " tenant=" + (tenantId == null ? "none" : tenantId.value()));
    }

    private static ConfiguredEntry tenant(TenantId tenantId, SecretReference ref, String value) {
        return new ConfiguredEntry(ScopeKind.TENANT, tenantId, ref, SecretPurpose.WHATSAPP_ACCESS_TOKEN, value);
    }

    private static ConfiguredEntry deployment(SecretReference ref, SecretPurpose purpose, String value) {
        return new ConfiguredEntry(ScopeKind.DEPLOYMENT, null, ref, purpose, value);
    }
}
