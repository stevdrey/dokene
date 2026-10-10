// ABOUTME: Execution scoped provider context storage backed by ScopedValue.
// ABOUTME: Mirrors ScopedValueTenantContextProvider for the no member webhook path.
package io.github.stevdrey.dokene.tenant.application;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ScopedValueProviderContextProvider implements ProviderContextProvider {
    private static final ScopedValue<ProviderContext> CURRENT = ScopedValue.newInstance();

    @Override
    public Optional<ProviderContext> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    @Override
    public <T, X extends Throwable> T callWithContext(ProviderContext context,
            TenantContextProvider.ScopedOperation<T, X> operation) throws X {
        Objects.requireNonNull(context, "Provider context is required");
        Objects.requireNonNull(operation, "Operation is required");
        return ScopedValue.where(CURRENT, context).call(operation::execute);
    }
}
