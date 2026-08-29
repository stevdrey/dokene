package io.github.stevdrey.dokene.tenant.application;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Execution-scoped tenant context storage backed by {@link ScopedValue}.
 */
@Component
public class ScopedValueTenantContextProvider implements TenantContextProvider {

    private static final ScopedValue<TenantContext> CURRENT_CONTEXT = ScopedValue.newInstance();

    @Override
    public Optional<TenantContext> current() {
        return CURRENT_CONTEXT.isBound() ? Optional.of(CURRENT_CONTEXT.get()) : Optional.empty();
    }

    @Override
    public TenantContext requireCurrent() {
        return CURRENT_CONTEXT.orElseThrow(TenantContextUnavailableException::new);
    }

    @Override
    public void runWithContext(TenantContext context, Runnable operation) {
        Objects.requireNonNull(context, "Tenant context is required");
        Objects.requireNonNull(operation, "Operation is required");
        ScopedValue.where(CURRENT_CONTEXT, context).run(operation);
    }

    @Override
    public <T, X extends Throwable> T callWithContext(TenantContext context, ScopedOperation<T, X> operation) throws X {
        Objects.requireNonNull(context, "Tenant context is required");
        Objects.requireNonNull(operation, "Operation is required");
        return ScopedValue.where(CURRENT_CONTEXT, context).call(operation::execute);
    }
}
