package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * PostgreSQL security-definer adapter for global tenant discovery.
 */
@Repository
public class JpaTenantMembershipDiscoveryAdapter implements TenantMembershipDiscovery {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseContextSigner databaseContextSigner;

    JpaTenantMembershipDiscoveryAdapter(JdbcTemplate jdbcTemplate, DatabaseContextSigner databaseContextSigner) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseContextSigner = databaseContextSigner;
    }

    @Override
    public List<ActiveTenantMembership> findActiveMemberships(IdentityId identityId) {
        SignedDatabaseContext context = databaseContextSigner.issueIdentityContext(identityId);
        return jdbcTemplate.query("""
                        SELECT tenant_id, tenant_display_name, membership_id, role
                        FROM dokene.discover_active_tenant_memberships(?, ?)
                        """,
                (resultSet, rowNumber) -> new ActiveTenantMembership(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getString("tenant_display_name"),
                        new TenantMembershipId(resultSet.getObject("membership_id", UUID.class)),
                        TenantRole.valueOf(resultSet.getString("role"))
                ),
                context.payload(),
                context.signature()
        );
    }
}
