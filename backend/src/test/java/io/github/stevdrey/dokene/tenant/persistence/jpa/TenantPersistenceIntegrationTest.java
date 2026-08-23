package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private JpaTenantRepositoryAdapter tenantRepository;

    @Autowired
    private JpaTenantMembershipRepositoryAdapter membershipRepository;

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void persistsAndRestoresTenantAndMembershipState() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = Tenant.create(TenantId.random(), "  Main workspace  ", createdAt);
        tenant.suspend(createdAt.plusSeconds(60));
        tenantRepository.save(tenant);

        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(),
                tenant.id(),
                new IdentityId(UUID.randomUUID()),
                TenantRole.ADMIN,
                createdAt
        );
        membership.suspend(createdAt.plusSeconds(120));
        membershipRepository.save(membership);

        Tenant restoredTenant = tenantRepository.findById(tenant.id()).orElseThrow();
        TenantMembership restoredMembership = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), membership.identityId())
                .orElseThrow();

        assertThat(restoredTenant.displayName()).isEqualTo("Main workspace");
        assertThat(restoredTenant.status()).isEqualTo(tenant.status());
        assertThat(restoredTenant.createdAt()).isEqualTo(createdAt);
        assertThat(restoredTenant.updatedAt()).isEqualTo(createdAt.plusSeconds(60));
        assertThat(restoredMembership.id()).isEqualTo(membership.id());
        assertThat(restoredMembership.status()).isEqualTo(membership.status());
        assertThat(restoredMembership.createdAt()).isEqualTo(createdAt);
        assertThat(restoredMembership.updatedAt()).isEqualTo(createdAt.plusSeconds(120));
    }

    @Test
    void permitsTheSameIdentityInDifferentTenants() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        Tenant firstTenant = persistTenant("First workspace", createdAt);
        Tenant secondTenant = persistTenant("Second workspace", createdAt);

        membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), firstTenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));
        membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), secondTenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));

        assertThat(membershipRepository.findByTenantIdAndIdentityId(firstTenant.id(), identityId)).isPresent();
        assertThat(membershipRepository.findByTenantIdAndIdentityId(secondTenant.id(), identityId)).isPresent();
    }

    @Test
    void rejectsDuplicateMembershipsForTheSameTenantAndIdentity() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        IdentityId identityId = new IdentityId(UUID.randomUUID());

        membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.OPERATOR, createdAt
        ));

        assertThatThrownBy(() -> membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.VIEWER, createdAt
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsMembershipsForUnknownTenants() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");

        assertThatThrownBy(() -> membershipRepository.save(TenantMembership.createActive(
                TenantMembershipId.random(), TenantId.random(), new IdentityId(UUID.randomUUID()),
                TenantRole.OPERATOR, createdAt
        ))).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Tenant persistTenant(String displayName, Instant createdAt) {
        Tenant tenant = Tenant.create(TenantId.random(), displayName, createdAt);
        tenantRepository.save(tenant);
        return tenant;
    }
}
