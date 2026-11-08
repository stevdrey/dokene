// ABOUTME: Issue 325: with a single pooled connection, a membership change refused under its locks still answers 403
// ABOUTME: and persists its denial, because the audit runs only after the change's transaction released the connection.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Uses the fixture's pool of one connection on purpose: a second one requested under the locks could never arrive. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MembershipChangeAuditPoolIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired private TenantRepository tenants;
    @Autowired private TenantMembershipRepository memberships;
    @Autowired private TenantContextProvider contexts;
    @Autowired private AuditExecutionContext execution;
    @Autowired private MembershipService membershipService;
    @Autowired private DatabaseContextSigner signer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @Test
    void aRefusalUnderTheLockIsDeniedAndAuditedWithASingleConnection() throws Exception {
        Tenant tenant = TenantSecurityIntegrationFixture.seedTenant(tenants, contexts,
                "Membership audit pool " + UUID.randomUUID(), CREATED_AT);
        TenantMembership admin = seed(tenant, TenantRole.ADMIN);
        TenantMembership viewer = seed(tenant, TenantRole.VIEWER);
        TenantContext resolved = TenantSecurityIntegrationFixture.context(admin);
        UUID correlation = UUID.randomUUID();

        try (Connection demotion = TenantSecurityIntegrationFixture.runtimeConnection(
                signer.issueTenantContext(tenant.id()))) {
            demotion.setAutoCommit(false);
            TenantSecurityIntegrationFixture.executeUpdate(demotion, """
                    UPDATE dokene.tenant_memberships SET role = 'VIEWER', updated_at = now(), version = version + 1
                    WHERE id = ?
                    """, admin.id().value());

            CompletableFuture<Void> revoke = CompletableFuture.runAsync(() -> execution.runWithCorrelation(correlation,
                    () -> contexts.runWithContext(resolved,
                            () -> membershipService.revokeMembership(viewer.identityId()))));
            MembershipChangeAuthorityIntegrationTest.awaitBlockedBy(demotion, revoke);
            demotion.commit();

            assertThatThrownBy(() -> revoke.get(MembershipChangeAuthorityIntegrationTest.TIMEOUT_SECONDS,
                    TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(TenantAccessDeniedException.class);
        }

        assertThat(MembershipChangeAuthorityIntegrationTest.auditRows(correlation)).containsExactly(
                "AUTHORIZATION_DENIED|DENIED|MEMBERSHIP_REVOKE|" + admin.id().value());
    }

    private TenantMembership seed(Tenant tenant, TenantRole role) {
        return TenantSecurityIntegrationFixture.seedMembership(memberships, contexts, tenant.id(),
                new IdentityId(UUID.randomUUID()), role, CREATED_AT);
    }
}
