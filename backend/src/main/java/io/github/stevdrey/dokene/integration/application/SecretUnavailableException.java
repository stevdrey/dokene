// ABOUTME: The one failure shape of IntegrationSecretResolver: a closed category, the scope kind and the tenant id.
// ABOUTME: Carries no cause, no reference string and no value, so its message and toString are safe to log.
package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Deployment;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretScope;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Objects;
import java.util.Optional;

public final class SecretUnavailableException extends RuntimeException {
    /** Why the secret could not be handed out. Consumers map every category to the same safe refusal. */
    public enum Category {
        MISSING,
        WRONG_PURPOSE,
        SOURCE_DISABLED
    }

    /** Which namespace the caller asked in, derived from the scope record's type. */
    public enum ScopeKind {
        TENANT,
        DEPLOYMENT
    }

    private final Category category;
    private final ScopeKind scopeKind;
    private final TenantId tenantId;

    public SecretUnavailableException(Category category, SecretScope scope) {
        this(category, kindOf(scope), tenantOf(scope));
    }

    private SecretUnavailableException(Category category, ScopeKind scopeKind, TenantId tenantId) {
        super(message(category, scopeKind, tenantId));
        this.category = category;
        this.scopeKind = scopeKind;
        this.tenantId = tenantId;
    }

    public Category category() {
        return category;
    }

    public ScopeKind scopeKind() {
        return scopeKind;
    }

    /** Empty for the deployment scope. */
    public Optional<TenantId> tenantId() {
        return Optional.ofNullable(tenantId);
    }

    /** The log friendly tenant field: the UUID, or {@code none} for the deployment scope. */
    public String tenantField() {
        return tenantId == null ? "none" : tenantId.value().toString();
    }

    private static String message(Category category, ScopeKind scopeKind, TenantId tenantId) {
        Objects.requireNonNull(category, "Category is required");
        return "Secret unavailable: category=" + category + " scope=" + scopeKind + " tenant="
                + (tenantId == null ? "none" : tenantId.value());
    }

    private static ScopeKind kindOf(SecretScope scope) {
        Objects.requireNonNull(scope, "Scope is required");
        return switch (scope) {
            case Tenant _ -> ScopeKind.TENANT;
            case Deployment _ -> ScopeKind.DEPLOYMENT;
        };
    }

    private static TenantId tenantOf(SecretScope scope) {
        return scope instanceof Tenant tenant ? tenant.tenantId() : null;
    }
}
