package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Default configurable implementation of {@link ProvisioningAuthorizationPolicy}.
 */
@Component
public class DefaultProvisioningAuthorizationPolicy implements ProvisioningAuthorizationPolicy {

    private final ProvisioningProperties.Mode mode;
    private final Set<UUID> allowedIdentities;

    public DefaultProvisioningAuthorizationPolicy(ProvisioningProperties properties) {
        Objects.requireNonNull(properties, "Provisioning properties are required");
        this.mode = properties.mode();
        this.allowedIdentities = properties.allowedIdentityIds();
    }

    @Override
    public boolean isAllowed(IdentityId identityId) {
        Objects.requireNonNull(identityId, "Identity ID is required");
        return switch (mode) {
            case DISABLED -> false;
            case OPEN -> true;
            case ALLOWLIST -> allowedIdentities.contains(identityId.value());
        };
    }
}
