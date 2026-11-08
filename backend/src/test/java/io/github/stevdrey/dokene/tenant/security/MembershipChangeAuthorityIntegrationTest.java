// ABOUTME: PostgreSQL regression suite for issue 325: a membership change is decided against the actor's membership
// ABOUTME: as committed when it writes, serialized by row locks with concurrent role changes, revocations and peers.
package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.executeUpdate;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.queryInt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.MembershipService;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MembershipChangeAuthorityIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-11-01T00:00:00Z");
    static final long TIMEOUT_SECONDS = 20;
    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String LOCK_TIMEOUT = "SET LOCAL lock_timeout = '500ms'";

    @Autowired private TenantRepository tenants;
    @MockitoSpyBean private TenantMembershipRepository memberships;
    @Autowired private TenantContextProvider contexts;
    @Autowired private AuditExecutionContext execution;
    @Autowired private MembershipService membershipService;
    @Autowired private DatabaseContextSigner signer;

    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final CountDownLatch paused = new CountDownLatch(1);
    private Tenant tenant;
    private TenantMembership admin;
    private TenantMembership viewer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        // Two changes running at once hold one connection each; a denial is audited only after its change released one.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @BeforeEach
    void seedTenantWithAdminAndViewer() {
        tenant = seedTenant();
        admin = seedMembership(tenant, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);
        viewer = seedMembership(tenant, new IdentityId(UUID.randomUUID()), TenantRole.VIEWER);
    }

    @Test
    void aDemotionCommittedWhileTheChangeWaitsRefusesItAndAuditsTheDenial() throws Exception {
        UUID correlation = UUID.randomUUID();
        try (Connection demotion = openTransaction(tenant)) {
            setRole(demotion, admin, TenantRole.VIEWER);

            CompletableFuture<Void> revoke = runAs(admin, correlation, () -> membershipService.revokeMembership(
                    viewer.identityId()));
            awaitBlockedBy(demotion, revoke);
            demotion.commit();

            assertThatThrownBy(() -> revoke.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TenantAccessDeniedException.class);
        }

        assertMembershipRow(viewer, TenantRole.VIEWER, TenantMembershipStatus.ACTIVE);
        assertThat(auditRows(correlation)).containsExactly(
                "AUTHORIZATION_DENIED|DENIED|MEMBERSHIP_REVOKE|" + admin.id().value());
    }

    @Test
    void aRevocationCommittedWhileTheChangeWaitsRefusesEveryKindOfChange() throws Exception {
        IdentityId invitee = new IdentityId(UUID.randomUUID());
        for (Change change : List.<Change>of(
                () -> membershipService.addMembership(invitee, TenantRole.VIEWER),
                () -> membershipService.changeRole(viewer.identityId(), TenantRole.OPERATOR),
                () -> membershipService.reactivateMembership(viewer.identityId()))) {
            TenantMembership actor = seedMembership(tenant, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);
            try (Connection revocation = openTransaction(tenant)) {
                setStatus(revocation, actor, TenantMembershipStatus.REVOKED);

                CompletableFuture<Void> attempt = runAs(actor, UUID.randomUUID(), change::run);
                awaitBlockedBy(revocation, attempt);
                revocation.commit();

                assertThatThrownBy(() -> attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(TenantAccessDeniedException.class);
            }
        }

        assertThat(memberships.findByTenantIdAndIdentityId(tenant.id(), invitee)).isEmpty();
        assertMembershipRow(viewer, TenantRole.VIEWER, TenantMembershipStatus.ACTIVE);
    }

    @Test
    void aDemotionThatRollsBackLetsTheWaitingChangeProceed() throws Exception {
        try (Connection demotion = openTransaction(tenant)) {
            setRole(demotion, admin, TenantRole.VIEWER);

            CompletableFuture<Void> change = runAs(admin, UUID.randomUUID(),
                    () -> membershipService.changeRole(viewer.identityId(), TenantRole.OPERATOR));
            awaitBlockedBy(demotion, change);
            demotion.rollback();

            change.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertMembershipRow(viewer, TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE);
        assertMembershipRow(admin, TenantRole.ADMIN, TenantMembershipStatus.ACTIVE);
    }

    @Test
    void twoAdminsRevokingEachOtherConcurrentlyNeverBothSucceedNorDeadlock() throws Exception {
        TenantMembership peer = seedMembership(tenant, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Void> first = runAs(admin, UUID.randomUUID(), () -> {
            await(start);
            membershipService.revokeMembership(peer.identityId());
        });
        CompletableFuture<Void> second = runAs(peer, UUID.randomUUID(), () -> {
            await(start);
            membershipService.revokeMembership(admin.identityId());
        });
        start.countDown();

        List<Throwable> failures = new ArrayList<>();
        for (CompletableFuture<Void> attempt : List.of(first, second)) {
            try {
                attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                failures.add(exception.getCause());
            }
        }

        assertThat(failures).singleElement().isInstanceOf(TenantAccessDeniedException.class);
        assertThat(revokedCount(admin, peer)).isEqualTo(1);
    }

    @Test
    void aPendingChangeToTheSameIdentityInAnotherTenantNeitherBlocksNorAuthorizesTheChange() throws Exception {
        Tenant other = seedTenant();
        TenantMembership adminElsewhere = seedMembership(other, admin.identityId(), TenantRole.ADMIN);
        TenantMembership revokedHere = seedMembership(tenant, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);
        seedMembership(other, revokedHere.identityId(), TenantRole.OWNER);

        try (Connection foreign = openTransaction(other)) {
            // Holds the admin identity's row in the other tenant, as a pending demotion there would.
            setRole(foreign, adminElsewhere, TenantRole.VIEWER);

            runAs(admin, UUID.randomUUID(), () -> membershipService.changeRole(viewer.identityId(),
                    TenantRole.OPERATOR)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            foreign.rollback();
        }
        try (Connection revocation = openTransaction(tenant)) {
            setStatus(revocation, revokedHere, TenantMembershipStatus.REVOKED);
            revocation.commit();
        }
        // Still OWNER of the other tenant, but revoked here: the request's stale ADMIN context authorizes nothing.
        CompletableFuture<Void> crossTenant = runAs(revokedHere, UUID.randomUUID(),
                () -> membershipService.revokeMembership(viewer.identityId()));
        assertThatThrownBy(() -> crossTenant.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .hasCauseInstanceOf(TenantAccessDeniedException.class);

        assertMembershipRow(viewer, TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE);
        assertMembershipRow(adminElsewhere, TenantRole.ADMIN, TenantMembershipStatus.ACTIVE);
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void aDemotionStartedAfterTheChangeLockedWaitsUntilTheChangeCommits() throws Exception {
        CountDownLatch release = pauseAfterLocksOnReadOf(viewer.identityId());
        CompletableFuture<Void> change = runAs(admin, UUID.randomUUID(),
                () -> membershipService.changeRole(viewer.identityId(), TenantRole.OPERATOR));
        assertThat(paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        try (Connection demotion = openTransaction(tenant)) {
            executeUpdate(demotion, LOCK_TIMEOUT);
            assertLockNotAvailable(() -> setRole(demotion, admin, TenantRole.VIEWER));
            demotion.rollback();

            release.countDown();
            change.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            setRole(demotion, admin, TenantRole.VIEWER);
            demotion.commit();
        }

        assertMembershipRow(viewer, TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE);
        assertMembershipRow(admin, TenantRole.VIEWER, TenantMembershipStatus.ACTIVE);
    }

    @Test
    void aTenantSuspensionWaitsForTheChangeButAnotherMembershipChangeDoesNot() throws Exception {
        TenantMembership otherAdmin = seedMembership(tenant, new IdentityId(UUID.randomUUID()), TenantRole.ADMIN);
        TenantMembership otherTarget = seedMembership(tenant, new IdentityId(UUID.randomUUID()), TenantRole.VIEWER);
        CountDownLatch release = pauseAfterLocksOnReadOf(viewer.identityId());
        CompletableFuture<Void> change = runAs(admin, UUID.randomUUID(),
                () -> membershipService.changeRole(viewer.identityId(), TenantRole.OPERATOR));
        assertThat(paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

        try (Connection suspension = openTransaction(tenant)) {
            executeUpdate(suspension, LOCK_TIMEOUT);
            assertLockNotAvailable(() -> executeUpdate(suspension, """
                    UPDATE dokene.tenants SET status = 'SUSPENDED', updated_at = now(), version = version + 1
                    WHERE id = ?
                    """, tenant.id().value()));
            suspension.rollback();
        }
        // The tenant lock is shared, so a change of other rows finishes while the first one still holds it.
        runAs(otherAdmin, UUID.randomUUID(), () -> membershipService.changeRole(otherTarget.identityId(),
                TenantRole.OPERATOR)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        release.countDown();
        change.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertMembershipRow(viewer, TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE);
        assertMembershipRow(otherTarget, TenantRole.OPERATOR, TenantMembershipStatus.ACTIVE);
    }

    /** Holds a change at its read of {@code target}, which comes after the locks and the fresh check. */
    private CountDownLatch pauseAfterLocksOnReadOf(IdentityId target) {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            paused.countDown();
            assertThat(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(memberships).findByTenantIdAndIdentityId(any(), eq(target));
        return release;
    }

    private static void assertLockNotAvailable(SqlAction action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(SQLException.class,
                exception -> assertThat(exception.getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE));
    }

    private Tenant seedTenant() {
        return TenantSecurityIntegrationFixture.seedTenant(tenants, contexts,
                "Membership authority " + UUID.randomUUID(), CREATED_AT);
    }

    private TenantMembership seedMembership(Tenant owner, IdentityId identity, TenantRole role) {
        return TenantSecurityIntegrationFixture.seedMembership(memberships, contexts, owner.id(), identity, role,
                CREATED_AT);
    }

    /** Runs {@code change} as {@code actor}, with the context its request resolved before any concurrent change. */
    private CompletableFuture<Void> runAs(TenantMembership actor, UUID correlation, Runnable change) {
        TenantContext resolved = TenantSecurityIntegrationFixture.context(actor);
        return CompletableFuture.runAsync(() -> execution.runWithCorrelation(correlation,
                () -> contexts.runWithContext(resolved, change)), pool);
    }

    /** A runtime-role transaction in {@code owner}'s tenant, like a concurrent membership change in flight. */
    private Connection openTransaction(Tenant owner) throws SQLException {
        Connection connection = TenantSecurityIntegrationFixture.runtimeConnection(signer.issueTenantContext(owner.id()));
        connection.setAutoCommit(false);
        return connection;
    }

    private static void setRole(Connection connection, TenantMembership membership, TenantRole role)
            throws SQLException {
        assertThat(executeUpdate(connection, """
                UPDATE dokene.tenant_memberships SET role = ?, updated_at = now(), version = version + 1 WHERE id = ?
                """, role.name(), membership.id().value())).isEqualTo(1);
    }

    private static void setStatus(Connection connection, TenantMembership membership, TenantMembershipStatus status)
            throws SQLException {
        assertThat(executeUpdate(connection, """
                UPDATE dokene.tenant_memberships SET status = ?, updated_at = now(), version = version + 1 WHERE id = ?
                """, status.name(), membership.id().value())).isEqualTo(1);
    }

    /** Waits until {@code blocker}'s transaction is what holds some other session, the change, on a lock. */
    static void awaitBlockedBy(Connection blocker, CompletableFuture<Void> change) throws Exception {
        int blockerPid = queryInt(blocker, "SELECT pg_backend_pid()");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            while (queryInt(connection, "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    blockerPid) == 0) {
                if (change.isDone()) {
                    change.get();
                    throw new AssertionError("The membership change finished without waiting for the row lock");
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("The membership change never waited for the row lock");
                }
                Thread.sleep(10);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static int revokedCount(TenantMembership first, TenantMembership second) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            return queryInt(connection, """
                    SELECT count(*) FROM dokene.tenant_memberships WHERE id IN (?, ?) AND status = 'REVOKED'
                    """, first.id().value(), second.id().value());
        }
    }

    static List<String> auditRows(UUID correlation) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT event_type, outcome, permission, membership_id
                        FROM dokene.audit_events WHERE correlation_id = ? ORDER BY occurred_at
                        """)) {
            statement.setObject(1, correlation);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<String> rows = new ArrayList<>();
                while (resultSet.next()) {
                    rows.add(String.join("|", resultSet.getString(1), resultSet.getString(2),
                            resultSet.getString(3), resultSet.getString(4)));
                }
                return rows;
            }
        }
    }

    private static void assertMembershipRow(TenantMembership membership, TenantRole role,
            TenantMembershipStatus status) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection()) {
            assertThat(queryInt(connection, """
                    SELECT count(*) FROM dokene.tenant_memberships WHERE id = ? AND role = ? AND status = ?
                    """, membership.id().value(), role.name(), status.name())).isEqualTo(1);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }

    @FunctionalInterface
    private interface Change {
        void run();
    }
}
