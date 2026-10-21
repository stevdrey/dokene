// ABOUTME: Full context proof of spec 0006 AC-9: the local source boots on the real stack with two provisioned
// ABOUTME: tenants, tenant A resolves its token, tenant B is MISSING, the deployment token resolves, logs stay clean.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.integration.testsupport.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.ResolvedSecret;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.ScopeKind;
import io.github.stevdrey.dokene.integration.secrets.LocalIntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import io.github.stevdrey.dokene.tenant.TenantTestSupport;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class IntegrationSecretResolutionIntegrationTest {
    private static final TenantId TENANT_A = TenantId.random();
    private static final TenantId TENANT_B = TenantId.random();
    private static final String TOKEN_REF = SecretCanaries.ref();
    private static final String VERIFY_REF = SecretCanaries.ref();
    private static final String TOKEN_A = SecretCanaries.value();
    private static final String VERIFY_TOKEN = SecretCanaries.value();
    private static final List<String> CANARIES = List.of(TOKEN_REF, VERIFY_REF, TOKEN_A, VERIFY_TOKEN);
    // Tenants are read under row level security, so a lookup cannot tell whether they exist; seed exactly once.
    private static final AtomicBoolean SEEDED = new AtomicBoolean();

    /** Values go through the registry, not annotation text, so a context failure report cannot print them. */
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("dokene.integration.secrets.source", () -> "local");
        registry.add("dokene.integration.secrets.local.entries[0].scope", () -> "tenant");
        registry.add("dokene.integration.secrets.local.entries[0].tenant-id", () -> TENANT_A.value().toString());
        registry.add("dokene.integration.secrets.local.entries[0].ref", () -> TOKEN_REF);
        registry.add("dokene.integration.secrets.local.entries[0].purpose", () -> "WHATSAPP_ACCESS_TOKEN");
        registry.add("dokene.integration.secrets.local.entries[0].value", () -> TOKEN_A);
        registry.add("dokene.integration.secrets.local.entries[1].scope", () -> "deployment");
        registry.add("dokene.integration.secrets.local.entries[1].ref", () -> VERIFY_REF);
        registry.add("dokene.integration.secrets.local.entries[1].purpose", () -> "WEBHOOK_VERIFY_TOKEN");
        registry.add("dokene.integration.secrets.local.entries[1].value", () -> VERIFY_TOKEN);
    }

    @Autowired IntegrationSecretResolver resolver;
    @Autowired TenantRepository tenants;
    @Autowired TenantContextProvider contexts;

    private LogCapture logs;

    /** The console capture starts before SpringExtension loads the context, so binding and startup output count. */
    @AfterAll
    static void startupOutputIsCanaryFree(CapturedOutput output) {
        assertThat(output.getAll()).contains("Integration secret source=LOCAL entries=2");
        assertThat(containsAny(output.getAll(), CANARIES)).isFalse();
    }

    @BeforeEach
    void setUp() {
        if (SEEDED.compareAndSet(false, true)) {
            TenantTestSupport.saveTenant(tenants, contexts, Tenant.create(TENANT_A, "Secrets A", Instant.now()));
            TenantTestSupport.saveTenant(tenants, contexts, Tenant.create(TENANT_B, "Secrets B", Instant.now()));
        }
        logs = new LogCapture();
    }

    @AfterEach
    void tearDown() {
        boolean leaked = containsAny(logs.text(), CANARIES);
        logs.close();
        assertThat(leaked).isFalse();
    }

    @Test
    void theLocalSourceIsTheOneResolverBean() {
        assertThat(resolver).isInstanceOf(LocalIntegrationSecretResolver.class);
    }

    @Test
    void tenantAResolvesItsOwnTokenAndTenantBIsDenied() {
        var scopeA = new IntegrationSecretResolver.Tenant(TENANT_A, SecretPurpose.WHATSAPP_ACCESS_TOKEN);
        var scopeB = new IntegrationSecretResolver.Tenant(TENANT_B, SecretPurpose.WHATSAPP_ACCESS_TOKEN);
        var reference = new SecretReference(TOKEN_REF);

        try (ResolvedSecret secret = resolver.resolve(scopeA, reference)) {
            assertThat(secret.<String>withValue(String::new)).isEqualTo(TOKEN_A);
        }
        SecretUnavailableException denial = catchThrowableOfType(SecretUnavailableException.class,
                () -> resolver.resolve(scopeB, reference));

        assertThat(denial.category()).isEqualTo(Category.MISSING);
        assertThat(denial.scopeKind()).isEqualTo(ScopeKind.TENANT);
        assertThat(denial.tenantId()).contains(TENANT_B);
        assertThat(containsAny(denial.getMessage() + denial, CANARIES)).isFalse();
        var warnings = logs.warningsFrom(LocalIntegrationSecretResolver.class);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.getFirst().getFormattedMessage())
                .isEqualTo("Secret unavailable category=MISSING scope=TENANT tenant=" + TENANT_B.value());
    }

    @Test
    void theDeploymentVerifyTokenResolvesAndIsMissingForTenants() {
        try (ResolvedSecret secret = resolver.resolve(new Deployment(SecretPurpose.WEBHOOK_VERIFY_TOKEN),
                new SecretReference(VERIFY_REF))) {
            assertThat(secret.<String>withValue(String::new)).isEqualTo(VERIFY_TOKEN);
        }
        SecretUnavailableException denial = catchThrowableOfType(SecretUnavailableException.class,
                () -> resolver.resolve(new IntegrationSecretResolver.Tenant(TENANT_A, SecretPurpose.WHATSAPP_ACCESS_TOKEN),
                        new SecretReference(VERIFY_REF)));

        assertThat(denial.category()).isEqualTo(Category.MISSING);
        assertThat(denial.tenantId()).contains(TENANT_A);
    }
}
