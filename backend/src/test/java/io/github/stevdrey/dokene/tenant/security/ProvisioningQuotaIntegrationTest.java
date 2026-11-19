// ABOUTME: Tests the per-identity provisioning cap against real PostgreSQL (issue 312, ADR 0033 §6): the cap holds,
// ABOUTME: replays and other identities are unaffected, and a refusal writes only one denial row after rollback.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.tenant.application.IdempotencyConflictException;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService.ProvisionedWorkspace;
import io.github.stevdrey.dokene.tenant.application.WorkspaceQuotaExceededException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProvisioningQuotaIntegrationTest {
    private static final int CAP = 2;

    @Autowired private WorkspaceProvisioningService provisioningService;
    @Autowired private AuditExecutionContext execution;
    @Autowired private MeterRegistry meters;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("dokene.provisioning.enabled", () -> "true");
        registry.add("dokene.provisioning.allow-all-identities", () -> "true");
        registry.add("dokene.provisioning.max-workspaces-per-identity", () -> String.valueOf(CAP));
    }

    /**
     * covers: #312 quota. The shared fixture gives the pool one connection, so this also proves the denial row is
     * written after the provisioning transaction has released its connection.
     */
    @Test
    void anIdentityAtTheCapIsRefusedWithOneDenialRowAndNothingElseWritten() throws SQLException {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        provision(identity, "key-1");
        provision(identity, "key-2");
        String refusedName = "Over Cap " + UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        double refusedBefore = quotaRefusals();

        execution.runWithCorrelation(correlation, () ->
                assertThatThrownBy(() -> provisioningService.provisionWorkspace(identity, "key-3", refusedName))
                        .isInstanceOf(WorkspaceQuotaExceededException.class));

        assertThat(count("SELECT count(*) FROM dokene.tenants WHERE display_name = ?", refusedName)).isZero();
        assertThat(count("SELECT count(*) FROM dokene.tenant_memberships WHERE identity_id = ?", identity.value()))
                .isEqualTo(CAP);
        assertThat(count("SELECT count(*) FROM dokene.workspace_provisioning_records WHERE identity_id = ?",
                identity.value())).isEqualTo(CAP);
        assertThat(count("SELECT count(*) FROM dokene.audit_events WHERE correlation_id = ?", correlation))
                .isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM dokene.audit_events
                WHERE correlation_id = ? AND event_type = 'AUTHORIZATION_DENIED' AND outcome = 'DENIED'
                  AND permission = 'TENANT_READ' AND denial_reason = 'NO_TENANT_CONTEXT'
                  AND tenant_id IS NULL AND actor_id IS NULL AND membership_id IS NULL
                """, correlation)).isEqualTo(1);
        assertThat(quotaRefusals()).isEqualTo(refusedBefore + 1);
    }

    /** covers: #312 quota. A replay at the cap answers as before: the existing workspace, or a name conflict. */
    @Test
    void replaysAtTheCapReturnTheExistingWorkspaceOrConflict() {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        ProvisionedWorkspace first = provision(identity, "key-1");
        provision(identity, "key-2");

        ProvisionedWorkspace replay = provisioningService.provisionWorkspace(identity, "key-1", first.displayName());

        assertThat(replay.created()).isFalse();
        assertThat(replay.tenantId()).isEqualTo(first.tenantId());
        assertThatThrownBy(() -> provisioningService.provisionWorkspace(identity, "key-1", "Renamed"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    /** covers: #312 quota. Another identity's workspaces never count toward this one's cap. */
    @Test
    void theCapIsPerIdentity() {
        IdentityId full = new IdentityId(UUID.randomUUID());
        provision(full, "key-1");
        provision(full, "key-2");

        ProvisionedWorkspace other = provision(new IdentityId(UUID.randomUUID()), "key-1");

        assertThat(other.created()).isTrue();
    }

    private ProvisionedWorkspace provision(IdentityId identity, String key) {
        ProvisionedWorkspace result = provisioningService.provisionWorkspace(
                identity, key, "Quota Workspace " + UUID.randomUUID());
        assertThat(result.created()).isTrue();
        return result;
    }

    private double quotaRefusals() {
        var counter = meters.find("dokene.provisioning.refused").tag("reason", "quota").counter();
        return counter == null ? 0 : counter.count();
    }

    private static int count(String sql, Object... parameters) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            return TenantSecurityIntegrationFixture.queryInt(connection, sql, parameters);
        }
    }
}
