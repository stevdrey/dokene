package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.executeUpdate;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryUuids;
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
import java.sql.Timestamp;
import java.time.Instant;
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
 * assertions observe PostgreSQL grants, RLS policies, and security-definer checks alone. RLS violations and missing
 * grants share SQLState 42501, so each assertion also pins the message to keep the controls separately observable.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantRootRowLevelSecurityIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RECORD_FUNCTION =
            "SELECT 1 FROM dokene.record_workspace_provisioning(?, ?, ?, ?, ?, ?, ?)";

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
            assertThat(queryInt(connection, "SELECT count(*) FROM dokene.tenants")).isZero();
            assertThat(executeUpdate(connection, "UPDATE dokene.tenants SET display_name = 'Tampered' WHERE id = ?",
                    workspaceB.tenantId().value())).isZero();
            assertThat(executeUpdate(connection, "UPDATE dokene.tenants SET status = 'ARCHIVED' WHERE id = ?",
                    workspaceB.tenantId().value())).isZero();

            assertRejectedByRls(() -> insertTenant(connection, TenantId.random()));
            assertPermissionDenied(() -> executeUpdate(connection, "DELETE FROM dokene.tenants WHERE id = ?",
                    workspaceB.tenantId().value()));

            assertPermissionDenied(() -> queryInt(connection, "SELECT count(*) FROM dokene.workspace_provisioning_records"));
            assertPermissionDenied(() -> insertProvisioningRecordDirectly(connection, identityB, workspaceB.tenantId()));
        }

        assertTenantUnchanged(workspaceB);
    }

    @Test
    void verifiedTenantContextIsLimitedToItsOwnTenantRow() throws Exception {
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnection(
                signer.issueTenantContext(workspaceA.tenantId()))) {
            assertThat(queryUuids(connection, "SELECT id FROM dokene.tenants"))
                    .containsExactly(workspaceA.tenantId().value());

            assertThat(executeUpdate(connection, "UPDATE dokene.tenants SET status = 'ARCHIVED' WHERE id = ?",
                    workspaceB.tenantId().value())).isZero();
            assertThat(executeUpdate(connection, "UPDATE dokene.tenants SET display_name = 'Tampered' WHERE id = ?",
                    workspaceB.tenantId().value())).isZero();

            assertRejectedByRls(() -> insertTenant(connection, TenantId.random()));
            assertPermissionDenied(() -> executeUpdate(connection, "UPDATE dokene.tenants SET id = ? WHERE id = ?",
                    workspaceB.tenantId().value(), workspaceA.tenantId().value()));
            assertPermissionDenied(() -> executeUpdate(connection,
                    "UPDATE dokene.tenants SET created_at = created_at - INTERVAL '1 day' WHERE id = ?",
                    workspaceA.tenantId().value()));
            assertPermissionDenied(() -> executeUpdate(connection, "DELETE FROM dokene.tenants WHERE id = ?",
                    workspaceA.tenantId().value()));

            assertPermissionDenied(() -> queryInt(connection, "SELECT count(*) FROM dokene.workspace_provisioning_records"));
            assertPermissionDenied(() -> insertProvisioningRecordDirectly(connection, identityA, workspaceA.tenantId()));

            assertThat(executeUpdate(connection,
                    "UPDATE dokene.tenants SET display_name = ?, version = version + 1 WHERE id = ?",
                    "Renamed A", workspaceA.tenantId().value())).isEqualTo(1);
        }

        assertTenantUnchanged(workspaceB);
    }

    @Test
    void provisioningLookupRequiresTheMatchingIdentityCapability() throws Exception {
        SignedDatabaseContext identityContextA = signer.issueIdentityContext(identityA);

        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext()) {
            assertThat(lookup(connection, identityContextA, idempotencyKeyA)).containsExactly(workspaceA.tenantId().value());
            assertThat(lookup(connection, signer.issueIdentityContext(identityB), idempotencyKeyA)).isEmpty();
            assertThat(lookup(connection, signer.issueTenantContext(workspaceA.tenantId()), idempotencyKeyA)).isEmpty();
            assertThat(lookup(connection, forged(identityContextA), idempotencyKeyA)).isEmpty();
            assertThat(queryUuids(connection,
                    "SELECT tenant_id FROM dokene.find_workspace_provisioning_record(NULL, NULL, ?)", idempotencyKeyA))
                    .isEmpty();
        }
    }

    @Test
    void provisioningRecordWriteRequiresBothTheIdentityCapabilityAndTheRecordedTenantContext() throws Exception {
        SignedDatabaseContext identityContextA = signer.issueIdentityContext(identityA);

        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext()) {
            assertCapabilityRejected(() -> recordProvisioning(connection, identityContextA, "no-tenant", workspaceA.tenantId()));
        }

        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnection(
                signer.issueTenantContext(workspaceA.tenantId()))) {
            assertCapabilityRejected(() -> recordProvisioning(connection, identityContextA, "other-tenant", workspaceB.tenantId()));
            assertCapabilityRejected(() -> recordProvisioning(connection, forged(identityContextA), "forged", workspaceA.tenantId()));
            assertCapabilityRejected(() -> recordProvisioning(connection,
                    signer.issueTenantContext(workspaceA.tenantId()), "tenant-scope", workspaceA.tenantId()));

            String acceptedKey = "accepted-" + UUID.randomUUID();
            recordProvisioning(connection, identityContextA, acceptedKey, workspaceA.tenantId());
            assertThat(lookup(connection, identityContextA, acceptedKey)).containsExactly(workspaceA.tenantId().value());
        }

        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM dokene.workspace_provisioning_records WHERE tenant_id = ? AND identity_id <> ?",
                    workspaceA.tenantId().value(), identityA.value())).isZero();
            assertThat(queryInt(connection,
                    "SELECT count(*) FROM dokene.workspace_provisioning_records WHERE tenant_id = ?",
                    workspaceB.tenantId().value())).isEqualTo(1);
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

    private static void assertRejectedByRls(SqlAction action) {
        assertInsufficientPrivilege(action, "row-level security");
    }

    private static void assertPermissionDenied(SqlAction action) {
        assertInsufficientPrivilege(action, "permission denied");
    }

    private static void assertCapabilityRejected(SqlAction action) {
        assertInsufficientPrivilege(action, "Workspace provisioning capability rejected");
    }

    private static void assertInsufficientPrivilege(SqlAction action, String expectedMessage) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(SQLException.class, exception -> assertThat(exception.getSQLState())
                        .isEqualTo(INSUFFICIENT_PRIVILEGE))
                .hasMessageContaining(expectedMessage);
    }

    private static SignedDatabaseContext forged(SignedDatabaseContext context) {
        return new SignedDatabaseContext(context.payload(), "0".repeat(64), context.expiresAt());
    }

    private static int insertTenant(Connection connection, TenantId tenantId) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        return executeUpdate(connection,
                "INSERT INTO dokene.tenants (id, display_name, status, created_at, updated_at) VALUES (?, 'Injected', 'ACTIVE', ?, ?)",
                tenantId.value(), now, now);
    }

    private static int insertProvisioningRecordDirectly(Connection connection, IdentityId identityId, TenantId tenantId)
            throws SQLException {
        return executeUpdate(connection, """
                        INSERT INTO dokene.workspace_provisioning_records
                            (id, idempotency_key, identity_id, tenant_id, display_name, created_at)
                        VALUES (?, ?, ?, ?, 'Injected', ?)
                        """,
                UUID.randomUUID(), "injected-" + UUID.randomUUID(), identityId.value(), tenantId.value(),
                Timestamp.from(Instant.now()));
    }

    private static void recordProvisioning(
            Connection connection,
            SignedDatabaseContext identityContext,
            String idempotencyKey,
            TenantId tenantId
    ) throws SQLException {
        queryInt(connection, RECORD_FUNCTION, identityContext.payload(), identityContext.signature(), UUID.randomUUID(),
                idempotencyKey, tenantId.value(), "Recorded", Timestamp.from(Instant.now()));
    }

    private static List<UUID> lookup(Connection connection, SignedDatabaseContext context, String idempotencyKey)
            throws SQLException {
        return queryUuids(connection, "SELECT tenant_id FROM dokene.find_workspace_provisioning_record(?, ?, ?)",
                context.payload(), context.signature(), idempotencyKey);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
