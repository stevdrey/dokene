// ABOUTME: Tests allowlist mode of workspace provisioning against real PostgreSQL (spec 0021): a listed identity
// ABOUTME: provisions, an unlisted one is refused with one global denial row and nothing else written.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.tenant.application.ProvisioningNotPermittedException;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService.ProvisionedWorkspace;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProvisioningAllowlistIntegrationTest {
    private static final IdentityId LISTED = new IdentityId(UUID.randomUUID());

    @Autowired private WorkspaceProvisioningService provisioningService;
    @Autowired private AuditExecutionContext execution;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("dokene.provisioning.enabled", () -> "true");
        registry.add("dokene.provisioning.allowed-identities", () -> LISTED.value().toString());
    }

    /** covers: AC-4 */
    @Test
    void aListedIdentityProvisionsAWorkspace() {
        ProvisionedWorkspace result = provisioningService.provisionWorkspace(
                LISTED, "key-" + UUID.randomUUID(), "Listed Workspace " + UUID.randomUUID());

        assertThat(result.created()).isTrue();
        assertThat(result.role()).isEqualTo(TenantRole.OWNER);
    }

    /**
     * covers: AC-4. The shared fixture gives the pool one connection, so this also proves the denial row is written
     * while no provisioning transaction holds a connection.
     */
    @Test
    void anUnlistedIdentityIsRefusedWithOneGlobalDenialRowAndNothingElseWritten() throws SQLException {
        IdentityId unlisted = new IdentityId(UUID.randomUUID());
        String workspaceName = "Refused Workspace " + UUID.randomUUID();
        UUID correlation = UUID.randomUUID();

        execution.runWithCorrelation(correlation, () ->
                assertThatThrownBy(() -> provisioningService.provisionWorkspace(unlisted, "key-refused", workspaceName))
                        .isInstanceOf(ProvisioningNotPermittedException.class));

        assertThat(count("SELECT count(*) FROM dokene.tenants WHERE display_name = ?", workspaceName)).isZero();
        assertThat(count("SELECT count(*) FROM dokene.tenant_memberships WHERE identity_id = ?", unlisted.value()))
                .isZero();
        assertThat(count("SELECT count(*) FROM dokene.workspace_provisioning_records WHERE identity_id = ?",
                unlisted.value())).isZero();
        assertThat(count("SELECT count(*) FROM dokene.audit_events WHERE correlation_id = ?", correlation))
                .isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM dokene.audit_events
                WHERE correlation_id = ? AND event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
                  AND permission = 'TENANT_READ' AND denial_reason = 'NO_TENANT_CONTEXT'
                  AND tenant_id IS NULL AND actor_id IS NULL AND membership_id IS NULL
                """, correlation)).isEqualTo(1);
    }

    private static int count(String sql, Object... parameters) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            return TenantSecurityIntegrationFixture.queryInt(connection, sql, parameters);
        }
    }
}
