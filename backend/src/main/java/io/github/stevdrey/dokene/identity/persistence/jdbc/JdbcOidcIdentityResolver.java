package io.github.stevdrey.dokene.identity.persistence.jdbc;

import io.github.stevdrey.dokene.identity.application.OidcIdentityMapping;
import io.github.stevdrey.dokene.identity.application.OidcIdentityResolver;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomic PostgreSQL adapter for OIDC-to-internal-identity mappings. */
@Repository
public class JdbcOidcIdentityResolver implements OidcIdentityResolver {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOidcIdentityResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public IdentityId resolve(OidcIdentityMapping mapping) {
        UUID identityId = jdbcTemplate.queryForObject(
                "SELECT dokene.resolve_oidc_identity(?, ?)",
                UUID.class,
                mapping.issuer().toString(),
                mapping.subject()
        );
        return new IdentityId(identityId);
    }
}
