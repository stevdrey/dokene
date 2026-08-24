package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantPersistenceIntegrationTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private JpaTenantRepositoryAdapter tenantRepository;

    @Autowired
    private JpaTenantMembershipRepositoryAdapter membershipRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        Tenant persistedTenant = tenantRepository.save(tenant);

        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(),
                tenant.id(),
                new IdentityId(UUID.randomUUID()),
                TenantRole.ADMIN,
                createdAt
        );
        membership.suspend(createdAt.plusSeconds(120));
        TenantMembership persistedMembership = membershipRepository.save(membership);

        Tenant restoredTenant = tenantRepository.findById(tenant.id()).orElseThrow();
        TenantMembership restoredMembership = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), membership.identityId())
                .orElseThrow();

        assertThat(restoredTenant.displayName()).isEqualTo("Main workspace");
        assertThat(restoredTenant.status()).isEqualTo(tenant.status());
        assertThat(restoredTenant.createdAt()).isEqualTo(createdAt);
        assertThat(restoredTenant.updatedAt()).isEqualTo(createdAt.plusSeconds(60));
        assertThat(persistedTenant.revision()).hasValue(0);
        assertThat(restoredTenant.revision()).hasValue(0);
        assertThat(restoredMembership.id()).isEqualTo(membership.id());
        assertThat(restoredMembership.status()).isEqualTo(membership.status());
        assertThat(restoredMembership.createdAt()).isEqualTo(createdAt);
        assertThat(restoredMembership.updatedAt()).isEqualTo(createdAt.plusSeconds(120));
        assertThat(persistedMembership.revision()).hasValue(0);
        assertThat(restoredMembership.revision()).hasValue(0);
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

    @Test
    void rejectsWhitespaceOnlyTenantNamesAtTheDatabaseBoundary() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");

        for (String displayName : new String[]{"\u0085", "\u00A0", "\u2007", "\u202F", "\u3000"}) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "INSERT INTO tenants (id, display_name, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), displayName, "ACTIVE", Timestamp.from(createdAt), Timestamp.from(createdAt)
            )).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void persistsDisplayNamesAtTheUnicodeCodePointLimit() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        String displayName = "😀".repeat(Tenant.DISPLAY_NAME_MAX_LENGTH);

        Tenant persistedTenant = tenantRepository.save(Tenant.create(TenantId.random(), displayName, createdAt));

        assertThat(persistedTenant.displayName()).isEqualTo(displayName);
        assertThat(tenantRepository.findById(persistedTenant.id()).orElseThrow().displayName()).isEqualTo(displayName);
    }

    @Test
    void synchronizesTenantRevisionWhenSavingTheSameInstance() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = Tenant.create(TenantId.random(), "Workspace", createdAt);

        tenantRepository.save(tenant);
        assertThat(tenant.revision()).hasValue(0);

        tenant.suspend(createdAt.plusSeconds(60));
        tenantRepository.save(tenant);
        assertThat(tenant.revision()).hasValue(1);

        tenant.activate(createdAt.plusSeconds(120));
        tenantRepository.save(tenant);

        assertThat(tenant.revision()).hasValue(2);
        assertThat(tenantRepository.findById(tenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void synchronizesMembershipRevisionWhenSavingTheSameInstance() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), new IdentityId(UUID.randomUUID()), TenantRole.OPERATOR, createdAt
        );

        membershipRepository.save(membership);
        assertThat(membership.revision()).hasValue(0);

        membership.suspend(createdAt.plusSeconds(60));
        membershipRepository.save(membership);
        assertThat(membership.revision()).hasValue(1);

        membership.revoke(createdAt.plusSeconds(120));
        membershipRepository.save(membership);

        assertThat(membership.revision()).hasValue(2);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), membership.identityId()).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void rejectsStaleMembershipUpdatesAfterRevocation() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = persistTenant("Workspace", createdAt);
        IdentityId identityId = new IdentityId(UUID.randomUUID());
        TenantMembership membership = TenantMembership.createActive(
                TenantMembershipId.random(), tenant.id(), identityId, TenantRole.OPERATOR, createdAt
        );
        membership.suspend(createdAt.plusSeconds(60));
        membershipRepository.save(membership);

        TenantMembership revocationCopy = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), identityId)
                .orElseThrow();
        TenantMembership staleActivationCopy = membershipRepository
                .findByTenantIdAndIdentityId(tenant.id(), identityId)
                .orElseThrow();

        revocationCopy.revoke(createdAt.plusSeconds(120));
        membershipRepository.save(revocationCopy);

        staleActivationCopy.activate(createdAt.plusSeconds(180));

        assertThatThrownBy(() -> membershipRepository.save(staleActivationCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(membershipRepository.findByTenantIdAndIdentityId(tenant.id(), identityId).orElseThrow().status())
                .isEqualTo(TenantMembershipStatus.REVOKED);
    }

    @Test
    void rejectsStaleTenantUpdatesAfterArchival() {
        Instant createdAt = Instant.parse("2026-08-23T00:00:00Z");
        Tenant tenant = Tenant.create(TenantId.random(), "Workspace", createdAt);
        tenant.suspend(createdAt.plusSeconds(60));
        tenantRepository.save(tenant);

        Tenant archivalCopy = tenantRepository.findById(tenant.id()).orElseThrow();
        Tenant staleActivationCopy = tenantRepository.findById(tenant.id()).orElseThrow();

        archivalCopy.archive(createdAt.plusSeconds(120));
        tenantRepository.save(archivalCopy);

        staleActivationCopy.activate(createdAt.plusSeconds(180));

        assertThatThrownBy(() -> tenantRepository.save(staleActivationCopy))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(tenantRepository.findById(tenant.id()).orElseThrow().status()).isEqualTo(TenantStatus.ARCHIVED);
    }

    private Tenant persistTenant(String displayName, Instant createdAt) {
        Tenant tenant = Tenant.create(TenantId.random(), displayName, createdAt);
        tenantRepository.save(tenant);
        return tenant;
    }
}
