package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextAuthorizationException;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import io.github.stevdrey.dokene.tenant.application.TenantMembershipDiscovery;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService.ProvisionedWorkspace;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import io.github.stevdrey.dokene.tenant.domain.WorkspaceProvisioningRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TenantProvisioningIntegrationTest {

    @Autowired private WorkspaceProvisioningService provisioningService;
    @Autowired private TenantMembershipDiscovery membershipDiscovery;
    @Autowired private TenantContextResolver contextResolver;
    @Autowired private TenantContextProvider contexts;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantMembershipRepository memberships;
    @Autowired private WorkspaceProvisioningRepository provisioningRecords;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("dokene.provisioning.enabled", () -> "true");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void permittedProvisioningRequestCreatesOneWorkspaceAndOneOwnerAtomically() {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        String idempotencyKey = "key-" + UUID.randomUUID();
        String workspaceName = "Acme Operations " + UUID.randomUUID().toString().substring(0, 8);

        ProvisionedWorkspace result = provisioningService.provisionWorkspace(identity, idempotencyKey, workspaceName);

        assertThat(result.created()).isTrue();
        assertThat(result.displayName()).isEqualTo(workspaceName);
        assertThat(result.role()).isEqualTo(TenantRole.OWNER);

        Tenant tenant = tenants.findById(result.tenantId()).orElseThrow();
        assertThat(tenant.displayName()).isEqualTo(workspaceName);
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);

        // Without tenant context, RLS blocks direct query and returns empty
        assertThat(memberships.findByTenantIdAndIdentityId(result.tenantId(), identity)).isEmpty();

        // Under verified tenant context, membership is accessible
        TenantMembership membership = contexts.callWithTenantId(result.tenantId(),
                () -> memberships.findByTenantIdAndIdentityId(result.tenantId(), identity).orElseThrow());
        assertThat(membership.role()).isEqualTo(TenantRole.OWNER);
        assertThat(membership.status()).isEqualTo(TenantMembershipStatus.ACTIVE);

        assertThat(provisioningRecords.findByIdentityIdAndIdempotencyKey(identity, idempotencyKey)).isPresent();

        List<TenantMembershipDiscovery.ActiveTenantMembership> activeMemberships = membershipDiscovery.findActiveMemberships(identity);
        assertThat(activeMemberships)
                .extracting(TenantMembershipDiscovery.ActiveTenantMembership::tenantId)
                .contains(result.tenantId());

        TenantContext resolvedContext = contextResolver.resolve(identity, result.tenantId());
        assertThat(resolvedContext.tenantId()).isEqualTo(result.tenantId());
        assertThat(resolvedContext.identityId()).isEqualTo(identity);
        assertThat(resolvedContext.role()).isEqualTo(TenantRole.OWNER);
    }

    @Test
    void failureDuringProvisioningRollsBackPartialState() {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        String idempotencyKey = "key-rollback-" + UUID.randomUUID();
        String workspaceName = "Rollback Workspace " + UUID.randomUUID().toString().substring(0, 8);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> template.execute(status -> {
            provisioningService.provisionWorkspace(identity, idempotencyKey, workspaceName);
            throw new RuntimeException("Simulated mid-flight failure");
        })).isInstanceOf(RuntimeException.class).hasMessage("Simulated mid-flight failure");

        assertThat(provisioningRecords.findByIdentityIdAndIdempotencyKey(identity, idempotencyKey)).isEmpty();
        Integer tenantCount = jdbc.queryForObject(
                "SELECT count(*) FROM dokene.tenants WHERE display_name = ?", Integer.class, workspaceName
        );
        assertThat(tenantCount).isZero();
    }

    @Test
    void retriesAndConcurrentDuplicateRequestsDoNotCreateDuplicateWorkspacesOrOwners() throws Exception {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        String idempotencyKey = "key-concurrent-" + UUID.randomUUID();
        String workspaceName = "Concurrent Workspace " + UUID.randomUUID().toString().substring(0, 8);

        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<ProvisionedWorkspace>> futures = new ArrayList<>();
            for (int index = 0; index < threadCount; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent provisioning");
                    }
                    return provisioningService.provisionWorkspace(identity, idempotencyKey, workspaceName);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ProvisionedWorkspace> results = new ArrayList<>();
            for (Future<ProvisionedWorkspace> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            ProvisionedWorkspace first = results.getFirst();
            for (ProvisionedWorkspace result : results) {
                assertThat(result.tenantId()).isEqualTo(first.tenantId());
                assertThat(result.displayName()).isEqualTo(workspaceName);
                assertThat(result.role()).isEqualTo(TenantRole.OWNER);
            }

            Integer tenantCount = jdbc.queryForObject(
                    "SELECT count(*) FROM dokene.tenants WHERE display_name = ?", Integer.class, workspaceName
            );
            assertThat(tenantCount).isEqualTo(1);

            Integer membershipCount = contexts.callWithTenantId(first.tenantId(), () -> jdbc.queryForObject(
                    "SELECT count(*) FROM dokene.tenant_memberships WHERE identity_id = ?", Integer.class, identity.value()
            ));
            assertThat(membershipCount).isEqualTo(1);

            Integer recordCount = jdbc.queryForObject(
                    "SELECT count(*) FROM dokene.workspace_provisioning_records WHERE identity_id = ? AND idempotency_key = ?",
                    Integer.class, identity.value(), idempotencyKey
            );
            assertThat(recordCount).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void selectionOfAnotherIdentityWorkspaceIsDenied() {
        IdentityId identityA = new IdentityId(UUID.randomUUID());
        IdentityId identityB = new IdentityId(UUID.randomUUID());
        String workspaceName = "Workspace A " + UUID.randomUUID().toString().substring(0, 8);

        ProvisionedWorkspace workspaceA = provisioningService.provisionWorkspace(
                identityA, "key-" + UUID.randomUUID(), workspaceName
        );

        assertThat(membershipDiscovery.findActiveMemberships(identityB))
                .extracting(TenantMembershipDiscovery.ActiveTenantMembership::tenantId)
                .doesNotContain(workspaceA.tenantId());

        assertThatThrownBy(() -> contextResolver.resolve(identityB, workspaceA.tenantId()))
                .isInstanceOf(TenantContextAuthorizationException.class);
    }

    @Test
    void revokedOrSuspendedMembershipCannotEstablishTenantContext() {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        ProvisionedWorkspace workspace = provisioningService.provisionWorkspace(
                identity, "key-" + UUID.randomUUID(), "Suspended Membership Workspace"
        );

        TenantMembership membership = contexts.callWithTenantId(workspace.tenantId(),
                () -> memberships.findByTenantIdAndIdentityId(workspace.tenantId(), identity).orElseThrow());
        membership.suspend(Instant.now());
        contexts.runWithTenantId(workspace.tenantId(), () -> memberships.save(membership));

        assertThat(membershipDiscovery.findActiveMemberships(identity))
                .extracting(TenantMembershipDiscovery.ActiveTenantMembership::tenantId)
                .doesNotContain(workspace.tenantId());

        assertThatThrownBy(() -> contextResolver.resolve(identity, workspace.tenantId()))
                .isInstanceOf(TenantContextAuthorizationException.class);

        // Transition to REVOKED
        membership.activate(Instant.now().plusSeconds(1));
        membership.revoke(Instant.now().plusSeconds(2));
        contexts.runWithTenantId(workspace.tenantId(), () -> memberships.save(membership));

        assertThat(membershipDiscovery.findActiveMemberships(identity))
                .extracting(TenantMembershipDiscovery.ActiveTenantMembership::tenantId)
                .doesNotContain(workspace.tenantId());

        assertThatThrownBy(() -> contextResolver.resolve(identity, workspace.tenantId()))
                .isInstanceOf(TenantContextAuthorizationException.class);
    }

    @Test
    void inactiveTenantCannotEstablishTenantContext() {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        ProvisionedWorkspace workspace = provisioningService.provisionWorkspace(
                identity, "key-" + UUID.randomUUID(), "Suspended Tenant Workspace"
        );

        Tenant tenant = tenants.findById(workspace.tenantId()).orElseThrow();
        tenant.suspend(Instant.now());
        tenants.save(tenant);

        assertThat(membershipDiscovery.findActiveMemberships(identity))
                .extracting(TenantMembershipDiscovery.ActiveTenantMembership::tenantId)
                .doesNotContain(workspace.tenantId());

        assertThatThrownBy(() -> contextResolver.resolve(identity, workspace.tenantId()))
                .isInstanceOf(TenantContextAuthorizationException.class);

        // Transition to ARCHIVED
        tenant.archive(Instant.now().plusSeconds(1));
        tenants.save(tenant);

        assertThat(membershipDiscovery.findActiveMemberships(identity))
                .extracting(TenantMembershipDiscovery.ActiveTenantMembership::tenantId)
                .doesNotContain(workspace.tenantId());

        assertThatThrownBy(() -> contextResolver.resolve(identity, workspace.tenantId()))
                .isInstanceOf(TenantContextAuthorizationException.class);
    }

    @Test
    void runtimeRoleCannotBypassRlsOrReadSigningKeys() throws Exception {
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext();
                Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("CREATE TABLE dokene.runtime_ddl (id INTEGER)"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("ALTER TABLE dokene.workspace_provisioning_records DISABLE ROW LEVEL SECURITY"))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeQuery("SELECT signing_key FROM dokene.tenant_context_signing_keys"))
                    .isInstanceOf(SQLException.class);
            statement.execute("SET row_security = off");
            assertThatThrownBy(() -> statement.executeQuery("SELECT id FROM dokene.tenant_memberships"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
