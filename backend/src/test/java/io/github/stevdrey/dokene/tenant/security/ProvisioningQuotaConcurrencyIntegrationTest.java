// ABOUTME: Races provisioning requests of one identity with distinct keys against real PostgreSQL (issue 312): the
// ABOUTME: per-identity lock lets exactly the cap succeed, and a previous-release (identity, key) lock is still honoured.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.tenant.application.WorkspaceProvisioningService;
import io.github.stevdrey.dokene.tenant.application.WorkspaceQuotaExceededException;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ProvisioningQuotaConcurrencyIntegrationTest {
    private static final int CAP = 2;
    private static final int REQUESTS = 6;

    @Autowired private WorkspaceProvisioningService provisioningService;
    @Autowired private AuditExecutionContext execution;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        // The shared fixture pins the pool to one connection, which would serialize the requests before they reach
        // the database. Re-registering the name replaces that value, so the requests really race here.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(REQUESTS + 2));
        registry.add("dokene.provisioning.enabled", () -> "true");
        registry.add("dokene.provisioning.allow-all-identities", () -> "true");
        registry.add("dokene.provisioning.max-workspaces-per-identity", () -> String.valueOf(CAP));
    }

    /** covers: #312 quota */
    @Test
    void concurrentRequestsWithDistinctKeysNeverExceedTheCap() throws Exception {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        ExecutorService executor = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch ready = new CountDownLatch(REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < REQUESTS; index++) {
                String key = "race-" + index;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start concurrent provisioning");
                    }
                    // A request filter opens this scope in production; a refusal's audit row needs it.
                    return execution.callWithCorrelation(UUID.randomUUID(),
                            () -> provisioningService.provisionWorkspace(identity, key, "Race Workspace " + key));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int created = 0;
            int refused = 0;
            for (Future<?> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    created++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(WorkspaceQuotaExceededException.class);
                    refused++;
                }
            }

            assertThat(created).isEqualTo(CAP);
            assertThat(refused).isEqualTo(REQUESTS - CAP);
            try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
                assertThat(TenantSecurityIntegrationFixture.queryInt(connection,
                        "SELECT count(*) FROM dokene.workspace_provisioning_records WHERE identity_id = ?",
                        identity.value())).isEqualTo(CAP);
                assertThat(TenantSecurityIntegrationFixture.queryInt(connection,
                        "SELECT count(*) FROM dokene.tenant_memberships WHERE identity_id = ?",
                        identity.value())).isEqualTo(CAP);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * covers: #312 quota. A backend of the previous release locks only (identity, key); a new backend must still wait
     * for it, so a retry split across the two during a rolling deploy replays instead of colliding.
     */
    @Test
    void waitsForThePreviousReleaseLockOnTheSameIdentityAndKey() throws Exception {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        String key = "rollout-key";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection oldBackend = TenantSecurityIntegrationFixture.migrationConnection()) {
            oldBackend.setAutoCommit(false);
            try (var statement = oldBackend.prepareStatement(
                    "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))")) {
                statement.setString(1, identity.value().toString());
                statement.setString(2, key);
                statement.execute();
            }

            Future<?> request = executor.submit(() -> execution.callWithCorrelation(UUID.randomUUID(),
                    () -> provisioningService.provisionWorkspace(identity, key, "Rollout Workspace")));
            Thread.sleep(500);
            assertThat(request.isDone()).isFalse();

            oldBackend.commit();
            request.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }
}
