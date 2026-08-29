package io.github.stevdrey.dokene.tenant.application;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Request-thread storage hidden behind {@link TenantContextProvider}.
 *
 * <p>This class deliberately uses {@link ThreadLocal} only as an implementation detail. It does
 * not use inheritable thread-local storage, so asynchronous work must establish context explicitly.</p>
 */
@Component
public class ThreadLocalTenantContextProvider implements TenantContextProvider {

    private final ThreadLocal<TenantContext> context = new ThreadLocal<>();

    @Override
    public Optional<TenantContext> current() {
        return Optional.ofNullable(context.get());
    }

    @Override
    public TenantContextScope establish(TenantContext tenantContext) {
        if (tenantContext == null) {
            throw new IllegalArgumentException("Tenant context is required");
        }
        if (context.get() != null) {
            throw new IllegalStateException("A tenant context is already active");
        }

        context.set(tenantContext);
        return new ClearingTenantContextScope();
    }

    private final class ClearingTenantContextScope implements TenantContextScope {

        private boolean closed;

        @Override
        public void close() {
            if (!closed) {
                context.remove();
                closed = true;
            }
        }
    }
}
