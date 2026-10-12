package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService.ProvisionedWorkspace;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Direct runtime-role SQL regression suite for the tenant root and workspace provisioning tables (issue #183).
 *
 * <p>Every statement runs on a raw {@code dokene_runtime} connection, bypassing the application layer, so the
 * assertions observe PostgreSQL grants and RLS policies alone.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantRootRowLevelSecurityIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Autowired private WorkspaceProvisioningService provisioningService;
    @Autowired private DatabaseContextSigner signer;

    private IdentityId identityA;
    private IdentityId identityB;
    private String idempotencyKeyA;
    private ProvisionedWorkspace workspaceA;
    private ProvisionedWorkspace workspaceB;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("dokene.provisioning.enabled", () -> "true");
    }

    @BeforeEach
    void provisionTwoWorkspaces() {
        identityA = new IdentityId(UUID.randomUUID());
        identityB = new IdentityId(UUID.randomUUID());
        idempotencyKeyA = "key-a-" + UUID.randomUUID();
        workspaceA = provisioningService.provisionWorkspace(identityA, idempotencyKeyA, "Root RLS A " + UUID.randomUUID());
        workspaceB = provisioningService.provisionWorkspace(identityB, "key-b-" + UUID.randomUUID(), "Root RLS B " + UUID.randomUUID());
    }

    @Test
    void runtimeRoleWithoutTenantContextFailsClosed() throws Exception {
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext()) {
            assertThat(count(connection, "SELECT count(*) FROM dokene.tenants")).isZero();
            assertThat(count(connection, "SELECT count(*) FROM dokene.workspace_provisioning_records")).isZero();

            assertThat(update(connection, "UPDATE dokene.tenants SET display_name = 'Tampered' WHERE id = ?",
                    workspaceB.tenantId().value())).isZero();
            assertThat(update(connection, "UPDATE dokene.tenants SET status = 'ARCHIVED' WHERE id = ?",
                    workspaceB.tenantId().value())).isZero();

            assertInsufficientPrivilege(() -> insertTenant(connection, TenantId.random()));
            assertInsufficientPrivilege(() -> insertProvisioningRecord(connection, identityB, workspaceB.tenantId()));
            assertInsufficientPrivilege(() -> update(connection, "DELETE FROM dokene.tenants WHERE id = ?",
                    workspaceB.tenantId().value()));
        }

        assertTenantUnchanged(workspaceB);
    }

    @Test
    void verifiedTenantContextIsLimitedToItsOwnRootRows() throws Exception {
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnection(
                signer.issueTenantContext(workspaceA.tenantId()))) {
            assertThat(uuids(connection, "SELECT id FROM dokene.tenants"))
                    .containsExactly(workspaceA.tenantId().value());
            assertThat(uuids(connection, "SELECT tenant_id FROM dokene.workspace_provisioning_records"))
                    .containsExactly(workspaceA.tenantId().value());

            assertThat(update(connection, "UPDATE dokene.tenants SET status = 'ARCHIVED' WHERE id = ?",
                    workspaceB.tenantId().value())).isZero();
            assertThat(update(connection, "UPDATE dokene.tenants SET display_name = 'Tampered' WHERE id = ?",
                    workspaceB.tenantId().value())).isZero();

            assertInsufficientPrivilege(() -> insertTenant(connection, TenantId.random()));
            assertInsufficientPrivilege(() -> insertProvisioningRecord(connection, identityA, workspaceB.tenantId()));
            assertInsufficientPrivilege(() -> update(connection, "UPDATE dokene.tenants SET id = ? WHERE id = ?",
                    workspaceB.tenantId().value(), workspaceA.tenantId().value()));
            assertInsufficientPrivilege(() -> update(connection,
                    "UPDATE dokene.tenants SET created_at = created_at - INTERVAL '1 day' WHERE id = ?",
                    workspaceA.tenantId().value()));
            assertInsufficientPrivilege(() -> update(connection, "DELETE FROM dokene.tenants WHERE id = ?",
                    workspaceA.tenantId().value()));

            assertThat(update(connection, "UPDATE dokene.tenants SET display_name = ?, version = version + 1 WHERE id = ?",
                    "Renamed A", workspaceA.tenantId().value())).isEqualTo(1);
        }

        assertTenantUnchanged(workspaceB);
    }

    @Test
    void provisioningLookupRequiresTheMatchingIdentityCapability() throws Exception {
        SignedDatabaseContext identityContextA = signer.issueIdentityContext(identityA);

        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext()) {
            assertThat(lookup(connection, identityContextA.payload(), identityContextA.signature(), idempotencyKeyA))
                    .containsExactly(workspaceA.tenantId().value());

            SignedDatabaseContext identityContextB = signer.issueIdentityContext(identityB);
            assertThat(lookup(connection, identityContextB.payload(), identityContextB.signature(), idempotencyKeyA)).isEmpty();

            SignedDatabaseContext tenantContextA = signer.issueTenantContext(workspaceA.tenantId());
            assertThat(lookup(connection, tenantContextA.payload(), tenantContextA.signature(), idempotencyKeyA)).isEmpty();

            String forgedSignature = "0".repeat(64);
            assertThat(lookup(connection, identityContextA.payload(), forgedSignature, idempotencyKeyA)).isEmpty();
            assertThat(lookup(connection, null, null, idempotencyKeyA)).isEmpty();
        }
    }

    private void assertTenantUnchanged(ProvisionedWorkspace workspace) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT display_name, status FROM dokene.tenants WHERE id = ?")) {
            statement.setObject(1, workspace.tenantId().value());
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString("display_name")).isEqualTo(workspace.displayName());
                assertThat(resultSet.getString("status")).isEqualTo("ACTIVE");
            }
        }
    }

    private static void assertInsufficientPrivilege(SqlAction action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .extracting(exception -> ((SQLException) exception).getSQLState())
                .isEqualTo(INSUFFICIENT_PRIVILEGE);
    }

    private static int insertTenant(Connection connection, TenantId tenantId) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        return update(connection,
                "INSERT INTO dokene.tenants (id, display_name, status, created_at, updated_at) VALUES (?, 'Injected', 'ACTIVE', ?, ?)",
                tenantId.value(), now, now);
    }

    private static int insertProvisioningRecord(Connection connection, IdentityId identityId, TenantId tenantId)
            throws SQLException {
        return update(connection, """
                        INSERT INTO dokene.workspace_provisioning_records
                            (id, idempotency_key, identity_id, tenant_id, display_name, created_at)
                        VALUES (?, ?, ?, ?, 'Injected', ?)
                        """,
                UUID.randomUUID(), "injected-" + UUID.randomUUID(), identityId.value(), tenantId.value(),
                Timestamp.from(Instant.now()));
    }

    private static List<UUID> lookup(Connection connection, String payload, String signature, String idempotencyKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tenant_id FROM dokene.find_workspace_provisioning_record(?, ?, ?)")) {
            statement.setString(1, payload);
            statement.setString(2, signature);
            statement.setString(3, idempotencyKey);
            return collectUuids(statement);
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static List<UUID> uuids(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return collectUuids(statement);
        }
    }

    private static List<UUID> collectUuids(PreparedStatement statement) throws SQLException {
        List<UUID> values = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                values.add(resultSet.getObject(1, UUID.class));
            }
        }
        return values;
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
