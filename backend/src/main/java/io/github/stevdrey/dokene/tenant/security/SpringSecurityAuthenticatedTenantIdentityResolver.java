package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.util.Optional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
class SpringSecurityAuthenticatedTenantIdentityResolver implements AuthenticatedTenantIdentityResolver {

    @Override
    public Optional<IdentityId> resolve(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        if (authentication.getPrincipal() instanceof AuthenticatedTenantIdentity identity) {
            return Optional.of(identity.identityId());
        }
        return Optional.empty();
    }
}
