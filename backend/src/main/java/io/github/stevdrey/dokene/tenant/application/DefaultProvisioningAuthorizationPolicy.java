package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Default configurable implementation of {@link ProvisioningAuthorizationPolicy}.
 */
@Component
public class DefaultProvisioningAuthorizationPolicy implements ProvisioningAuthorizationPolicy {

    private final boolean enabled;
    private final Set<UUID> allowedIdentities;

    public DefaultProvisioningAuthorizationPolicy(
            @Value("${dokene.provisioning.enabled:true}") boolean enabled,
            @Value("${dokene.provisioning.allowed-identities:}") List<String> allowedIdentities
    ) {
        this.enabled = enabled;
        this.allowedIdentities = allowedIdentities == null ? Set.of() : allowedIdentities.stream()
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isAllowed(IdentityId identityId) {
        Objects.requireNonNull(identityId, "Identity ID is required");
        if (!enabled) {
            return false;
        }
        if (allowedIdentities.isEmpty()) {
            return true;
        }
        return allowedIdentities.contains(identityId.value());
    }
}
