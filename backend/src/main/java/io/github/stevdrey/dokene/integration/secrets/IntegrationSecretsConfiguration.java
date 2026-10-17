// ABOUTME: Selects the one IntegrationSecretResolver bean from dokene.integration.secrets.source (spec 0006).
// ABOUTME: Validates the local entries at startup in a fixed order, naming only the entry index and field on failure.
package io.github.stevdrey.dokene.integration.secrets;

import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretPurpose;
import io.github.stevdrey.dokene.integration.application.IntegrationSecretResolver.SecretReference;
import io.github.stevdrey.dokene.integration.application.SecretUnavailableException.ScopeKind;
import io.github.stevdrey.dokene.integration.secrets.IntegrationSecretsProperties.Entry;
import io.github.stevdrey.dokene.integration.secrets.LocalIntegrationSecretResolver.ConfiguredEntry;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntegrationSecretsProperties.class)
public class IntegrationSecretsConfiguration {
    private static final Logger log = LoggerFactory.getLogger(IntegrationSecretsConfiguration.class);
    private static final String TENANT_SCOPE = "tenant";
    private static final String DEPLOYMENT_SCOPE = "deployment";

    /**
     * Exactly one resolver exists by construction: no conditional beans, no fallback between implementations. An
     * unsupported source or an invalid entry fails startup here, never later at send time.
     */
    @Bean
    IntegrationSecretResolver integrationSecretResolver(IntegrationSecretsProperties properties) {
        SecretSourceType source = SecretSourceType.fromProperty(properties.source());
        return switch (source) {
            case DISABLED -> {
                log.info("Integration secret source={} (every resolution is refused)", source);
                yield new DisabledIntegrationSecretResolver();
            }
            case LOCAL -> {
                List<ConfiguredEntry> entries = validate(properties.local().entries());
                // A count only: an empty list under `local` is the usual sign the entries never reached the process.
                log.info("Integration secret source={} entries={}", source, entries.size());
                yield new LocalIntegrationSecretResolver(entries);
            }
        };
    }

    private static List<ConfiguredEntry> validate(List<Entry> entries) {
        List<ConfiguredEntry> validated = new ArrayList<>(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            validated.add(validate(index, entries.get(index)));
        }
        Set<EntryKey> seen = new HashSet<>();
        for (int index = 0; index < validated.size(); index++) {
            ConfiguredEntry entry = validated.get(index);
            if (!seen.add(new EntryKey(entry.scopeKind(), entry.tenantId(), entry.ref()))) {
                throw InvalidSecretConfigurationException.duplicate(index, "ref");
            }
        }
        return List.copyOf(validated);
    }

    /** Field order is fixed (scope, tenant-id, ref, purpose, value) so the first violation is predictable. */
    private static ConfiguredEntry validate(int index, Entry entry) {
        if (entry == null) {
            throw InvalidSecretConfigurationException.invalid(index, "scope");
        }
        ScopeKind scopeKind = switch (entry.scope()) {
            case TENANT_SCOPE -> ScopeKind.TENANT;
            case DEPLOYMENT_SCOPE -> ScopeKind.DEPLOYMENT;
            case null, default -> throw InvalidSecretConfigurationException.invalid(index, "scope");
        };
        TenantId tenantId = tenantId(index, scopeKind, entry.tenantId());
        if (entry.ref() == null || !SecretReference.FORMAT.matcher(entry.ref()).matches()) {
            throw InvalidSecretConfigurationException.invalid(index, "ref");
        }
        SecretPurpose purpose = purpose(index, scopeKind, entry.purpose());
        if (entry.value() == null || entry.value().isBlank()) {
            throw InvalidSecretConfigurationException.invalid(index, "value");
        }
        return new ConfiguredEntry(scopeKind, tenantId, new SecretReference(entry.ref()), purpose, entry.value());
    }

    private static TenantId tenantId(int index, ScopeKind scopeKind, String raw) {
        if (scopeKind == ScopeKind.DEPLOYMENT) {
            if (raw != null) {
                throw InvalidSecretConfigurationException.invalid(index, "tenant-id");
            }
            return null;
        }
        if (raw == null) {
            throw InvalidSecretConfigurationException.invalid(index, "tenant-id");
        }
        try {
            return new TenantId(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            // Deliberately not chained: the JDK message would carry the raw text.
            throw InvalidSecretConfigurationException.invalid(index, "tenant-id");
        }
    }

    /** An exact constant name that the port's scope records admit for this scope (AC-1). */
    private static SecretPurpose purpose(int index, ScopeKind scopeKind, String raw) {
        SecretPurpose purpose;
        try {
            purpose = raw == null ? null : SecretPurpose.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            purpose = null;
        }
        if (purpose == null || !ConfiguredEntry.admits(scopeKind, purpose)) {
            throw InvalidSecretConfigurationException.invalid(index, "purpose");
        }
        return purpose;
    }

    private record EntryKey(ScopeKind scopeKind, TenantId tenantId, SecretReference ref) {
    }
}
