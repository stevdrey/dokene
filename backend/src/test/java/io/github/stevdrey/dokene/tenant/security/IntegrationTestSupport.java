// ABOUTME: Seeds an enabled tenant integration through the real service (spec 0005 AC-6), replacing the gate stub.
// ABOUTME: Each call registers a unique DOKENE_WA_TEST_<suffix> test property, since a reference is unique across tenants.
package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.integration.config.IntegrationConfigurationService;
import io.github.stevdrey.dokene.integration.config.IntegrationConfigurationUpdate;
import io.github.stevdrey.dokene.integration.config.TenantMessagingIntegration;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public final class IntegrationTestSupport {
    public static final String TEST_TOKEN = "test-token";

    private final IntegrationConfigurationService integrations;
    private final TenantContextProvider contexts;
    private final AuditExecutionContext auditExecution;
    private final ConfigurableEnvironment environment;

    public IntegrationTestSupport(IntegrationConfigurationService integrations, TenantContextProvider contexts,
            AuditExecutionContext auditExecution, ConfigurableEnvironment environment) {
        this.integrations = Objects.requireNonNull(integrations);
        this.contexts = Objects.requireNonNull(contexts);
        this.auditExecution = Objects.requireNonNull(auditExecution);
        this.environment = Objects.requireNonNull(environment);
    }

    /**
     * Adds a DOKENE_WA_TEST_<suffix> property the resolver finds and returns its reference, so every tenant of a
     * test run can hold its own name (the unique index refuses a name another tenant already saved).
     */
    public String registerSecret(String token) {
        String name = "DOKENE_WA_TEST_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        environment.getPropertySources().addFirst(new MapPropertySource("integration-test-" + name,
                Map.of(name, token)));
        return "env:" + name;
    }

    /** Saves a complete, enabled integration for the owner's tenant and opens the kill switch; version 2 after it. */
    public TenantMessagingIntegration enableIntegration(TenantContext owner) throws Exception {
        return enableIntegration(owner, registerSecret(TEST_TOKEN));
    }

    public TenantMessagingIntegration enableIntegration(TenantContext owner, String secretRef) throws Exception {
        String phoneNumberId = "1" + String.format("%014d", Math.abs(UUID.randomUUID().getLeastSignificantBits()
                % 100_000_000_000_000L));
        TenantMessagingIntegration saved = inContext(owner, () -> integrations.update(
                new IntegrationConfigurationUpdate(phoneNumberId, "200000000000000", "+56 9 2222 0000", "CL",
                        secretRef, true, integrations.read().version())));
        return inContext(owner, () -> integrations.setOutbound(true, saved.version()));
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
