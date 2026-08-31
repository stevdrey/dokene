package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Optional;

/**
 * Access point for the tenant context active in the current unit of execution.
 *
 * <p>Asynchronous and non-request work must establish a context explicitly through
 * {@link #runWithContext(TenantContext, Runnable)} or {@link #callWithContext(TenantContext, ScopedOperation)};
 * context is never inherited implicitly.</p>
 */
public interface TenantContextProvider {

    Optional<TenantContext> current();

    default Optional<TenantId> currentTenantId() {
        return current().map(TenantContext::tenantId);
    }

    default TenantContext requireCurrent() {
        return current().orElseThrow(TenantContextUnavailableException::new);
    }

    void runWithContext(TenantContext context, Runnable operation);

    <T, X extends Throwable> T callWithContext(TenantContext context, ScopedOperation<T, X> operation) throws X;

    void runWithTenantId(TenantId tenantId, Runnable operation);

    <T, X extends Throwable> T callWithTenantId(TenantId tenantId, ScopedOperation<T, X> operation) throws X;

    @FunctionalInterface
    interface ScopedOperation<T, X extends Throwable> {
        T execute() throws X;
    }
}
