// ABOUTME: PostgreSQL security-definer adapter for the invitee's pending invitations and their acceptance (V37).
// ABOUTME: Both calls carry a signed identity capability, so the database scopes them to that identity alone.
package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantInvitationDirectory;
import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery.ActiveTenantMembership;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTenantInvitationDirectoryAdapter implements TenantInvitationDirectory {

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseContextSigner databaseContextSigner;

    JdbcTenantInvitationDirectoryAdapter(JdbcTemplate jdbcTemplate, DatabaseContextSigner databaseContextSigner) {
        this.jdbcTemplate = jdbcTemplate;
        this.databaseContextSigner = databaseContextSigner;
    }

    @Override
    public List<PendingInvitation> findPendingInvitations(IdentityId identityId) {
        SignedDatabaseContext context = databaseContextSigner.issueIdentityContext(identityId);
        return jdbcTemplate.query("""
                        SELECT tenant_id, tenant_display_name, membership_id, role, invited_at
                        FROM dokene.discover_pending_tenant_invitations(?, ?)
                        """,
                (resultSet, rowNumber) -> new PendingInvitation(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getString("tenant_display_name"),
                        new TenantMembershipId(resultSet.getObject("membership_id", UUID.class)),
                        TenantRole.valueOf(resultSet.getString("role")),
                        resultSet.getObject("invited_at", OffsetDateTime.class).toInstant()
                ),
                context.payload(),
                context.signature()
        );
    }

    @Override
    public Optional<ActiveTenantMembership> accept(IdentityId identityId, TenantId tenantId) {
        SignedDatabaseContext context = databaseContextSigner.issueIdentityContext(identityId);
        List<ActiveTenantMembership> accepted = jdbcTemplate.query("""
                        SELECT tenant_id, tenant_display_name, membership_id, role
                        FROM dokene.accept_tenant_membership_invitation(?, ?, ?)
                        """,
                (resultSet, rowNumber) -> new ActiveTenantMembership(
                        new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                        resultSet.getString("tenant_display_name"),
                        new TenantMembershipId(resultSet.getObject("membership_id", UUID.class)),
                        TenantRole.valueOf(resultSet.getString("role"))
                ),
                context.payload(),
                context.signature(),
                tenantId.value()
        );
        return accepted.stream().findFirst();
    }
}
