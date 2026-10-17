// ABOUTME: Local, non production IntegrationSecretResolver over a startup validated list of configuration entries.
// ABOUTME: Keeps each value in its own char[] and hands out copies; a tenant scope can only ever match its own entries.
package io.github.stevdrey.dokene.integration.secrets;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.Category;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.ScopeKind;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalIntegrationSecretResolver implements IntegrationSecretResolver {
    private static final Logger log = LoggerFactory.getLogger(LocalIntegrationSecretResolver.class);

    /**
     * One entry after startup validation: the owner, the bare key, the purpose and the value exactly as bound.
     * {@code tenantId} is null for the deployment scope.
     */
    record ConfiguredEntry(ScopeKind scopeKind, TenantId tenantId, SecretReference ref, SecretPurpose purpose,
            String value) {
        ConfiguredEntry {
            Objects.requireNonNull(scopeKind, "Scope kind is required");
            Objects.requireNonNull(ref, "Reference is required");
            Objects.requireNonNull(purpose, "Purpose is required");
            Objects.requireNonNull(value, "Value is required");
            if ((scopeKind == ScopeKind.TENANT) != (tenantId != null)) {
                throw new IllegalArgumentException("A tenant entry needs a tenant id and a deployment entry has none");
            }
            if (!admits(scopeKind, purpose)) {
                throw new IllegalArgumentException("The scope does not admit this purpose");
            }
        }

        /** The port's scope records are the one source of truth for which purposes a scope admits (AC-1). */
        static boolean admits(ScopeKind scopeKind, SecretPurpose purpose) {
            try {
                switch (scopeKind) {
                    case TENANT -> new Tenant(TenantId.random(), purpose);
                    case DEPLOYMENT -> new Deployment(purpose);
                }
                return true;
            } catch (IllegalArgumentException | NullPointerException refused) {
                return false;
            }
        }

        @Override
        public String toString() {
            return "ConfiguredEntry[REDACTED]";
        }
    }

    /** The namespace plus the bare key. Purpose is on the stored value, so a wrong purpose differs from a miss. */
    private record Key(ScopeKind scopeKind, TenantId tenantId, SecretReference ref) {
    }

    private record Stored(SecretPurpose purpose, char[] value) {
        @Override
        public String toString() {
            return "Stored[REDACTED]";
        }
    }

    private final Map<Key, Stored> entries;

    LocalIntegrationSecretResolver(List<ConfiguredEntry> configured) {
        Objects.requireNonNull(configured, "Entries are required");
        Map<Key, Stored> stored = new HashMap<>();
        for (ConfiguredEntry entry : configured) {
            Key key = new Key(entry.scopeKind(), entry.tenantId(), entry.ref());
            if (stored.putIfAbsent(key, new Stored(entry.purpose(), entry.value().toCharArray())) != null) {
                throw new IllegalArgumentException("Duplicate secret entry");
            }
        }
        this.entries = Map.copyOf(stored);
    }

    @Override
    public ResolvedSecret resolve(SecretScope scope, SecretReference reference) {
        Objects.requireNonNull(scope, "Scope is required");
        Objects.requireNonNull(reference, "Reference is required");
        Stored stored = entries.get(keyOf(scope, reference));
        if (stored == null) {
            throw deny(Category.MISSING, scope);
        }
        if (stored.purpose() != scope.purpose()) {
            throw deny(Category.WRONG_PURPOSE, scope);
        }
        // ResolvedSecret wipes its argument, so every call gets its own copy and the stored value survives.
        return new ResolvedSecret(Arrays.copyOf(stored.value(), stored.value().length));
    }

    private static Key keyOf(SecretScope scope, SecretReference reference) {
        return switch (scope) {
            case Tenant tenant -> new Key(ScopeKind.TENANT, tenant.tenantId(), reference);
            case Deployment _ -> new Key(ScopeKind.DEPLOYMENT, null, reference);
        };
    }

    private static SecretUnavailableException deny(Category category, SecretScope scope) {
        var denial = new SecretUnavailableException(category, scope);
        log.warn("Secret unavailable category={} scope={} tenant={}", denial.category(), denial.scopeKind(),
                denial.tenantField());
        return denial;
    }
}
