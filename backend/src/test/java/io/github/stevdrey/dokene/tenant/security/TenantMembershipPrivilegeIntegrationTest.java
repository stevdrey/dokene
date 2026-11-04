package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.executeUpdate;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.MembershipService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Runtime-role privilege regression suite for {@code dokene.tenant_memberships} (issue #315, audit L5).
 *
 * <p>Direct SQL runs on a raw {@code dokene_runtime} connection bound to the membership's own tenant, so RLS allows
 * the row and only the grants decide. The legitimate invitation, role change and revocation flows run through the
 * application services against the same database.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantMembershipPrivilegeIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final Instant CREATED_AT = Instant.parse("2026-11-01T00:00:00Z");
    private static final List<String> UPDATABLE_COLUMNS = List.of("role", "status", "updated_at", "version");
    private static final List<String> IMMUTABLE_COLUMNS = List.of("id", "tenant_id", "identity_id", "created_at");

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMembershipRepository memberships;
    @Autowired private TenantContextProvider contexts;
    @Autowired private AuditExecutionContext execution;
    @Autowired private MembershipService membershipService;
    @Autowired private DatabaseContextSigner signer;

    private Tenant tenant;
    private TenantMembership admin;
    private TenantMembership viewer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @BeforeEach
    void seedTenantWithAdminAndViewer() {
        tenant = TenantSecurityIntegrationFixture.seedTenant(tenants, contexts, "Membership grants " + UUID.randomUUID(),
                CREATED_AT);
        admin = seedMembership(TenantRole.ADMIN);
        viewer = seedMembership(TenantRole.VIEWER);
    }

    @Test
    void runtimeRoleHoldsNoDeleteAndUpdatesOnlyTheLifecycleColumns() throws Exception {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            assertThat(tablePrivilege(connection, "SELECT")).isTrue();
            assertThat(tablePrivilege(connection, "INSERT")).isTrue();
            assertThat(tablePrivilege(connection, "UPDATE")).isFalse();
            assertThat(tablePrivilege(connection, "DELETE")).isFalse();
            assertThat(tablePrivilege(connection, "TRUNCATE")).isFalse();
            for (String column : UPDATABLE_COLUMNS) {
                assertThat(columnUpdatePrivilege(connection, column)).as(column).isTrue();
            }
            for (String column : IMMUTABLE_COLUMNS) {
                assertThat(columnUpdatePrivilege(connection, column)).as(column).isFalse();
            }
            assertThat(queryInt(connection, """
                    SELECT count(*) FROM pg_policies
                    WHERE schemaname = 'dokene' AND tablename = 'tenant_memberships' AND cmd = 'DELETE'
                    """)).isZero();
        }
    }

    @Test
    void runtimeRoleCannotDeleteOrRewriteImmutableColumnsOfItsOwnTenantsMemberships() throws Exception {
        UUID viewerId = viewer.id().value();
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnection(
                signer.issueTenantContext(tenant.id()))) {
            assertPermissionDenied(() -> executeUpdate(connection,
                    "DELETE FROM dokene.tenant_memberships WHERE id = ?", viewerId));
            assertPermissionDenied(() -> executeUpdate(connection, "DELETE FROM dokene.tenant_memberships"));
            assertPermissionDenied(() -> executeUpdate(connection,
                    "UPDATE dokene.tenant_memberships SET identity_id = ? WHERE id = ?", admin.identityId().value(),
                    viewerId));
            assertPermissionDenied(() -> executeUpdate(connection,
                    "UPDATE dokene.tenant_memberships SET tenant_id = tenant_id WHERE id = ?", viewerId));
            assertPermissionDenied(() -> executeUpdate(connection,
                    "UPDATE dokene.tenant_memberships SET id = ? WHERE id = ?", UUID.randomUUID(), viewerId));
            assertPermissionDenied(() -> executeUpdate(connection,
                    "UPDATE dokene.tenant_memberships SET created_at = created_at - INTERVAL '1 day' WHERE id = ?",
                    viewerId));
            assertPermissionDenied(() -> executeUpdate(connection, "TRUNCATE dokene.tenant_memberships"));
        }

        assertMembershipRow(viewer, TenantRole.VIEWER, TenantMembershipStatus.ACTIVE, CREATED_AT);

        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnection(
                signer.issueTenantContext(tenant.id()))) {
            assertThat(executeUpdate(connection, """
                    UPDATE dokene.tenant_memberships
                    SET role = 'OPERATOR', status = 'SUSPENDED', updated_at = now(), version = version + 1
                    WHERE id = ?
                    """, viewerId)).isEqualTo(1);
        }

        assertMembershipRow(viewer, TenantRole.OPERATOR, TenantMembershipStatus.SUSPENDED, CREATED_AT);
    }

    @Test
    void invitationRoleChangeAndRevocationStillPersistThroughTheApplication() throws Exception {
        TenantContext adminContext = TenantSecurityIntegrationFixture.context(admin);
        IdentityId invitee = new IdentityId(UUID.randomUUID());

        AtomicReference<TenantMembership> invitation = new AtomicReference<>();
        scoped(adminContext, () -> invitation.set(membershipService.addMembership(invitee, TenantRole.VIEWER)));
        TenantMembership invited = invitation.get();
        assertMembershipRow(invited, TenantRole.VIEWER, TenantMembershipStatus.ACTIVE, invited.createdAt());

        scoped(adminContext, () -> membershipService.changeRole(invitee, TenantRole.OPERATOR));
        assertMembershipRow(invited, TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE, invited.createdAt());

        scoped(adminContext, () -> membershipService.revokeMembership(invitee));
        assertMembershipRow(invited, TenantRole.OPERATOR, TenantMembershipStatus.REVOKED, invited.createdAt());
    }

    private TenantMembership seedMembership(TenantRole role) {
        return TenantSecurityIntegrationFixture.seedMembership(memberships, contexts, tenant.id(),
                new IdentityId(UUID.randomUUID()), role, CREATED_AT);
    }

    private void scoped(TenantContext context, Runnable operation) {
        execution.runWithCorrelation(UUID.randomUUID(), () -> contexts.runWithContext(context, operation));
    }

    private void assertMembershipRow(
            TenantMembership membership,
            TenantRole role,
            TenantMembershipStatus status,
            Instant createdAt
    ) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT tenant_id, identity_id, role, status, created_at
                        FROM dokene.tenant_memberships WHERE id = ?
                        """)) {
            statement.setObject(1, membership.id().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getObject("tenant_id", UUID.class)).isEqualTo(membership.tenantId().value());
                assertThat(resultSet.getObject("identity_id", UUID.class)).isEqualTo(membership.identityId().value());
                assertThat(resultSet.getString("role")).isEqualTo(role.name());
                assertThat(resultSet.getString("status")).isEqualTo(status.name());
                assertThat(resultSet.getTimestamp("created_at").toInstant()).isEqualTo(createdAt);
            }
        }
    }

    private static boolean tablePrivilege(Connection connection, String privilege) throws SQLException {
        return queryInt(connection,
                "SELECT CASE WHEN has_table_privilege('dokene_runtime', 'dokene.tenant_memberships', ?) THEN 1 ELSE 0 END",
                privilege) == 1;
    }

    private static boolean columnUpdatePrivilege(Connection connection, String column) throws SQLException {
        return queryInt(connection, """
                SELECT CASE WHEN has_column_privilege('dokene_runtime', 'dokene.tenant_memberships', ?, 'UPDATE')
                    THEN 1 ELSE 0 END
                """, column) == 1;
    }

    private static void assertPermissionDenied(SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SQLException.class, exception -> assertThat(exception.getSQLState())
                        .isEqualTo(INSUFFICIENT_PRIVILEGE))
                .hasMessageContaining("permission denied");
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
