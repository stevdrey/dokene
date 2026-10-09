package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Issue #159: the tenant-wide follow-up policy requires TENANT_UPDATE. This class has its own connection pool because
 * the denial is audited in an independent (REQUIRES_NEW) transaction while the @Transactional service call still holds
 * its connection, which cannot work with the single-connection pool of the shared fixture.
 */
@SpringBootTest(properties = "dokene.ai.provider=fake")
class FollowUpTenantPolicyAuthorizationIntegrationTest {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
    }

    @Autowired FollowUpService followUps;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;
    @MockitoSpyBean AuditRecorder auditRecorder;

    private Tenant tenant;
    private TenantContext ownerContext;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now();
        tenant = seedTenant(tenants, "Tenant policy authz " + UUID.randomUUID(), now);
        ownerContext = context(seedMembership(memberships, contexts, tenant.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
    }

    @ParameterizedTest
    @EnumSource(value = TenantRole.class, names = {"OPERATOR", "VIEWER"})
    void tenantPolicyUpdateIsDeniedAndAuditedWithTenantUpdate(TenantRole role) throws Exception {
        TenantContext roleContext = context(seedMembership(memberships, contexts, tenant.id(),
                new IdentityId(UUID.randomUUID()), role, Instant.now()));

        assertThatThrownBy(() -> inContext(roleContext,
                () -> followUps.configureTenant(21, ZoneId.of("UTC"), 0)))
                .isInstanceOf(TenantAccessDeniedException.class);

        verify(auditRecorder).authorizationDenied(eq(TenantPermission.TENANT_UPDATE), any());
        var policy = inContext(ownerContext, () -> followUps.tenantPolicy());
        assertThat(policy.version()).isZero();
        assertThat(policy.cadenceDays()).isNotEqualTo(21);
    }

    private <T> T inContext(TenantContext tenantContext, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(tenantContext, operation::call));
    }
}
