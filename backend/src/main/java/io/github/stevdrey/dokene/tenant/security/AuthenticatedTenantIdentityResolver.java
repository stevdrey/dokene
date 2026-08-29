package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.util.Optional;
import org.springframework.security.core.Authentication;

interface AuthenticatedTenantIdentityResolver {

    Optional<IdentityId> resolve(Authentication authentication);
}
