package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Execution-scoped tenant context storage backed by {@link ScopedValue}.
 */
@Component
public class ScopedValueTenantContextProvider implements TenantContextProvider {

    private static final ScopedValue<TenantContext> CURRENT_CONTEXT = ScopedValue.newInstance();
    private static final ScopedValue<TenantId> CURRENT_TENANT_ID = ScopedValue.newInstance();

    @Override
    public Optional<TenantContext> current() {
        return CURRENT_CONTEXT.isBound() ? Optional.of(CURRENT_CONTEXT.get()) : Optional.empty();
    }

    @Override
    public Optional<TenantId> currentTenantId() {
        if (CURRENT_CONTEXT.isBound()) {
            return Optional.of(CURRENT_CONTEXT.get().tenantId());
        }
        return CURRENT_TENANT_ID.isBound() ? Optional.of(CURRENT_TENANT_ID.get()) : Optional.empty();
    }

    @Override
    public TenantContext requireCurrent() {
        return CURRENT_CONTEXT.orElseThrow(TenantContextUnavailableException::new);
    }

    @Override
    public void runWithContext(TenantContext context, Runnable operation) {
        Objects.requireNonNull(context, "Tenant context is required");
        Objects.requireNonNull(operation, "Operation is required");
        ScopedValue.where(CURRENT_CONTEXT, context)
                .where(CURRENT_TENANT_ID, context.tenantId())
                .run(operation);
    }

    @Override
    public <T, X extends Throwable> T callWithContext(TenantContext context, ScopedOperation<T, X> operation) throws X {
        Objects.requireNonNull(context, "Tenant context is required");
        Objects.requireNonNull(operation, "Operation is required");
        return ScopedValue.where(CURRENT_CONTEXT, context)
                .where(CURRENT_TENANT_ID, context.tenantId())
                .call(operation::execute);
    }

    @Override
    public void runWithTenantId(TenantId tenantId, Runnable operation) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(operation, "Operation is required");
        ScopedValue.where(CURRENT_TENANT_ID, tenantId).run(operation);
    }

    @Override
    public <T, X extends Throwable> T callWithTenantId(TenantId tenantId, ScopedOperation<T, X> operation) throws X {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(operation, "Operation is required");
        return ScopedValue.where(CURRENT_TENANT_ID, tenantId).call(operation::execute);
    }
}
