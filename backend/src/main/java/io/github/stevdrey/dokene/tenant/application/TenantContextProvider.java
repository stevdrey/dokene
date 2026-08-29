package io.github.stevdrey.dokene.tenant.application;

import java.util.Optional;

/**
 * Access point for the tenant context active in the current unit of execution.
 *
 * <p>Asynchronous and non-request work must establish a context explicitly through
 * {@link #establish(TenantContext)}; context is never inherited by child threads.</p>
 */
public interface TenantContextProvider {

    Optional<TenantContext> current();

    default TenantContext requireCurrent() {
        return current().orElseThrow(TenantContextUnavailableException::new);
    }

    TenantContextScope establish(TenantContext context);
}
