package io.github.stevdrey.dokene.identity.application;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;

/** Resolves a validated provider identity to Dokene's stable, provider-neutral identity. */
public interface OidcIdentityResolver {

    IdentityId resolve(OidcIdentityMapping mapping);
}
