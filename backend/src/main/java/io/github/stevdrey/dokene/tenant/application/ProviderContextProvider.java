// ABOUTME: Access point for the provider context bound by the webhook ingestion boundary.
// ABOUTME: Never inherited implicitly; a caller binds it explicitly for one bounded operation.
package io.github.stevdrey.dokene.tenant.application;

import java.util.Optional;

public interface ProviderContextProvider {

    Optional<ProviderContext> current();

    default ProviderContext requireCurrent() {
        return current().orElseThrow(TenantContextUnavailableException::new);
    }

    <T, X extends Throwable> T callWithContext(ProviderContext context,
            TenantContextProvider.ScopedOperation<T, X> operation) throws X;
}
