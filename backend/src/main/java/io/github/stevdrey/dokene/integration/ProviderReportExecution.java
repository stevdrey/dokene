// ABOUTME: Runs one provider attributed operation for a tenant: binds the provider context and an audit correlation.
// ABOUTME: Shared by every adapter that reports delivery facts off the request thread; the tenant never comes from input.
package io.github.stevdrey.dokene.integration;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContext;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ProviderReportExecution {
    private final ProviderContextProvider providerContexts;
    private final AuditExecutionContext auditExecution;

    public ProviderReportExecution(ProviderContextProvider providerContexts, AuditExecutionContext auditExecution) {
        this.providerContexts = Objects.requireNonNull(providerContexts, "Provider context provider is required");
        this.auditExecution = Objects.requireNonNull(auditExecution, "Audit execution context is required");
    }

    public <T, X extends Throwable> T call(UUID tenantId, TenantContextProvider.ScopedOperation<T, X> operation)
            throws X {
        ProviderContext context = new ProviderContext(new TenantId(Objects.requireNonNull(tenantId)));
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> providerContexts.callWithContext(context, operation));
    }
}
