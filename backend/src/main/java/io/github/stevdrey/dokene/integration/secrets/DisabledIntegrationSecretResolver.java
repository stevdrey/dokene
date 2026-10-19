// ABOUTME: The fail closed stand in when no secret source is configured: every resolve is SOURCE_DISABLED.
// ABOUTME: Warns once, then stays quiet, so a tenant with a bad reference cannot flood the log through this path.
package io.github.stevdrey.dokene.integration.secrets;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DisabledIntegrationSecretResolver implements IntegrationSecretResolver {
    private static final Logger log = LoggerFactory.getLogger(DisabledIntegrationSecretResolver.class);

    // The bean is a singleton, so one flag per instance is one warning per process.
    private final AtomicBoolean warned = new AtomicBoolean();

    @Override
    public ResolvedSecret resolve(SecretScope scope, SecretReference reference) {
        Objects.requireNonNull(scope, "Scope is required");
        Objects.requireNonNull(reference, "Reference is required");
        var denial = new SecretUnavailableException(Category.SOURCE_DISABLED, scope);
        if (warned.compareAndSet(false, true)) {
            log.warn("Secret unavailable category={} scope={} tenant={}", denial.category(), denial.scopeKind(),
                    denial.tenantField());
        }
        throw denial;
    }
}
