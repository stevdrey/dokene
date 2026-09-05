package io.github.stevdrey.dokene.audit.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPage;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.AuditOutcome;
import io.github.stevdrey.dokene.tenant.application.AuthorizationAuditListener;
import io.github.stevdrey.dokene.tenant.application.AuthorizationDeniedEvent;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.MembershipRoleService;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class AuditIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();
    private static final String SIGNING_KEY = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");

    @Autowired private AuditRecorder recorder;
    @Autowired private AuthorizationAuditListener listener;
    @Autowired private AuditReader reader;
    @Autowired private AuditExecutionContext execution;
    @Autowired private TenantContextProvider contexts;
    @Autowired private TenantAuthorizationService authorization;
    @Autowired private MembershipRoleService roles;
    @Autowired private TenantRepository tenants;
    @MockitoSpyBean private TenantMembershipRepository memberships;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private DatabaseContextSigner signer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws SQLException {
        POSTGRES.start();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE dokene_migration LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + MIGRATION_PASSWORD + "'");
            statement.execute("CREATE ROLE dokene_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '" + RUNTIME_PASSWORD + "'");
            statement.execute("REVOKE ALL ON DATABASE " + POSTGRES.getDatabaseName() + " FROM PUBLIC");
            statement.execute("GRANT CONNECT, CREATE ON DATABASE " + POSTGRES.getDatabaseName() + " TO dokene_migration");
            statement.execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO dokene_runtime");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC");
        }
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "dokene_runtime");
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "dokene_migration");
        registry.add("spring.flyway.password", () -> MIGRATION_PASSWORD);
        registry.add("dokene.tenant-context.signing-key", () -> SIGNING_KEY);
    }

    @Test
    void changesRoleAndRecordsTrustedAttributionAtomically() {
        TenantContext context = tenant(TenantRole.ADMIN);
        IdentityId target = member(context, TenantRole.VIEWER);
        UUID correlation = UUID.randomUUID();
        execution.runWithCorrelation(correlation, () -> contexts.runWithContext(context, () -> {
            roles.changeRole(target, TenantRole.OPERATOR);
            AuditEvent event = reader.read().events().getFirst();
            assertThat(event.tenantId()).isEqualTo(context.tenantId());
            assertThat(event.actorId()).isEqualTo(context.identityId());
            assertThat(event.membershipId()).isEqualTo(context.membershipId());
            assertThat(event.correlationId()).isEqualTo(correlation);
            assertThat(event.type()).isEqualTo(AuditEventType.MEMBERSHIP_ROLE_CHANGED);
            assertThat(event.outcome()).isEqualTo(AuditOutcome.SUCCESS);
            assertThat(event.timestamp()).isBeforeOrEqualTo(Instant.now());
            TenantMembership changed = memberships.findByTenantIdAndIdentityId(context.tenantId(), target).orElseThrow();
            assertThat(changed.role()).isEqualTo(TenantRole.OPERATOR);
            assertThat(event.target().id()).isEqualTo(changed.id().value());
            assertThat(event.metadata()).isEqualTo(new AuditMetadata.MembershipRoleChanged(TenantRole.VIEWER, TenantRole.OPERATOR));
        }));
    }

    @Test
    void supportedListenerDiscardsSensitiveTextAndSuppliedAttributionBeforePersistence() {
        TenantContext context = tenant(TenantRole.ADMIN);
        UUID forged = UUID.randomUUID();
        String sensitive = "Bearer-secret-token-and-full-message-body";
        scoped(context, () -> {
            listener.onAuthorizationDenied(new AuthorizationDeniedEvent(Instant.EPOCH, new IdentityId(forged),
                    new TenantId(forged), new TenantMembershipId(forged), TenantRole.OWNER,
                    TenantPermission.AUDIT_READ, new TenantId(forged), sensitive));
            AuditEvent event = reader.read().events().getFirst();
            assertThat(event.tenantId()).isEqualTo(context.tenantId());
            assertThat(event.actorId()).isEqualTo(context.identityId());
            assertThat(event.membershipId()).isEqualTo(context.membershipId());
            assertThat(event.timestamp()).isAfter(Instant.EPOCH);
            assertThat(event.metadata()).isEqualTo(new AuditMetadata.AuthorizationDenied(
                    TenantPermission.AUDIT_READ, AuditDenialReason.UNSPECIFIED));
            String persisted = jdbc.queryForObject("SELECT row_to_json(a)::text FROM dokene.audit_events a", String.class);
            assertThat(persisted).doesNotContain(sensitive, forged.toString());
        });
    }

    @Test
    void denialSurvivesOuterRollbackAndDoesNotExposeForeignTenant() {
        TenantContext context = tenant(TenantRole.VIEWER);
        TenantId foreign = new TenantId(UUID.randomUUID());
        scoped(context, () -> {
            assertThatThrownBy(() -> transaction(() -> authorization.requireResourceAccess(TenantPermission.CUSTOMER_READ, foreign)))
                    .isInstanceOf(TenantAccessDeniedException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dokene.audit_events", Integer.class)).isEqualTo(1);
            String row = jdbc.queryForObject("SELECT row_to_json(a)::text FROM dokene.audit_events a", String.class);
            assertThat(row).contains(context.tenantId().value().toString(), "CROSS_TENANT_RESOURCE");
            assertThat(row).doesNotContain(foreign.value().toString());
        });
    }

    @Test
    void recordsGlobalDenialWithoutMakingItVisibleToRuntime() throws SQLException {
        UUID correlation = UUID.randomUUID();
        execution.runWithCorrelation(correlation, () -> {
            assertThatThrownBy(() -> authorization.requirePermission(TenantPermission.AUDIT_READ))
                    .isInstanceOf(TenantAccessDeniedException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dokene.audit_events", Integer.class)).isZero();
        });
        try (Connection connection = migration(); Statement statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM dokene.audit_events WHERE correlation_id = '" + correlation + "'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getObject("tenant_id")).isNull();
            assertThat(rows.getObject("actor_id")).isNull();
            assertThat(rows.getObject("membership_id")).isNull();
            assertThat(rows.getString("denial_reason")).isEqualTo("NO_TENANT_CONTEXT");
        }
        scoped(tenant(TenantRole.ADMIN), () -> assertThat(reader.read().events()).isEmpty());
    }

    @Test
    void protectsReadsAndAppendOnlyPermissions() {
        TenantContext first = tenant(TenantRole.ADMIN);
        TenantContext second = tenant(TenantRole.ADMIN);
        assertThat(jdbc.queryForMap("""
                SELECT c.relrowsecurity, c.relforcerowsecurity, r.rolname AS owner
                FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
                WHERE c.oid = 'dokene.audit_events'::regclass
                """))
                .containsEntry("relrowsecurity", true)
                .containsEntry("relforcerowsecurity", true)
                .containsEntry("owner", "dokene_migration");
        scoped(first, () -> {
            recorder.authorizationDenied(TenantPermission.TENANT_ARCHIVE, AuditDenialReason.INSUFFICIENT_PERMISSION);
            for (String sql : List.of("UPDATE dokene.audit_events SET outcome = 'SUCCESS'",
                    "DELETE FROM dokene.audit_events", "TRUNCATE dokene.audit_events")) {
                assertThatThrownBy(() -> jdbc.execute(sql)).isInstanceOf(RuntimeException.class);
            }
            assertThat(reader.read().events()).hasSize(1);
        });
        scoped(second, () -> {
            assertThat(reader.read().events()).isEmpty();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dokene.audit_events WHERE tenant_id = ?",
                    Integer.class, first.tenantId().value())).isZero();
            assertThatThrownBy(() -> insertDenial(first.tenantId(), "UNSPECIFIED", "AUDIT_READ"))
                    .isInstanceOf(RuntimeException.class);
        });
        scoped(tenant(TenantRole.VIEWER), () -> assertThatThrownBy(reader::read).isInstanceOf(TenantAccessDeniedException.class));
        scoped(tenant(TenantRole.OPERATOR), () -> assertThatThrownBy(reader::read).isInstanceOf(TenantAccessDeniedException.class));
    }

    @Test
    void missingAndForgedCapabilitiesCannotReadOrInsertTenantEvents() throws SQLException {
        TenantContext context = tenant(TenantRole.ADMIN);
        scoped(context, () -> recorder.authorizationDenied(TenantPermission.AUDIT_READ, AuditDenialReason.UNSPECIFIED));
        SignedDatabaseContext valid = signer.issueTenantContext(context.tenantId());
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "dokene_runtime", RUNTIME_PASSWORD)) {
            for (String signature : List.of("", "0".repeat(64))) {
                try (var settings = connection.prepareStatement("SELECT set_config('dokene.tenant_context', ?, false), set_config('dokene.tenant_context_signature', ?, false)")) {
                    settings.setString(1, signature.isEmpty() ? "" : valid.payload());
                    settings.setString(2, signature);
                    settings.execute();
                }
                try (Statement statement = connection.createStatement()) {
                    try (var rows = statement.executeQuery("SELECT count(*) FROM dokene.audit_events")) {
                        rows.next();
                        assertThat(rows.getInt(1)).isZero();
                    }
                    assertThatThrownBy(() -> statement.execute("""
                            INSERT INTO dokene.audit_events
                                (id, occurred_at, tenant_id, actor_id, membership_id, event_type, outcome, correlation_id, denial_reason)
                            VALUES (gen_random_uuid(), now(), '%s', gen_random_uuid(), gen_random_uuid(),
                                'AUTHORIZATION_DENIED', 'DENIED', gen_random_uuid(), 'UNSPECIFIED')
                            """.formatted(context.tenantId().value()))).isInstanceOf(SQLException.class);
                }
            }
        }
    }

    @Test
    void rejectsNoTenantReasonWithTenantAtApplicationAndDatabaseBoundaries() {
        scoped(tenant(TenantRole.ADMIN), () -> {
            assertThatThrownBy(() -> recorder.authorizationDenied(TenantPermission.AUDIT_READ, AuditDenialReason.NO_TENANT_CONTEXT))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> insertDenial(contexts.requireCurrent().tenantId(), "NO_TENANT_CONTEXT", "AUDIT_READ"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_audit_shape");
            assertThat(reader.read().events()).isEmpty();
            recorder.authorizationDenied(TenantPermission.AUDIT_READ, AuditDenialReason.UNSPECIFIED);
            assertThat(reader.read().events()).hasSize(1);
        });
    }

    @Test
    void rejectsUnsafeMetadataAtDatabaseBoundary() {
        scoped(tenant(TenantRole.ADMIN), () -> {
            TenantId tenant = contexts.requireCurrent().tenantId();
            assertThatThrownBy(() -> insertDenial(tenant, "raw-secret-token", "AUDIT_READ"))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> insertDenial(tenant, "UNSPECIFIED", "raw-request-body"))
                    .isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dokene.audit_events", Integer.class)).isZero();
        });
    }

    @Test
    void rollsBackRoleAndSuccessEventWhenBusinessTransactionRollsBack() {
        TenantContext context = tenant(TenantRole.ADMIN);
        IdentityId target = member(context, TenantRole.VIEWER);
        scoped(context, () -> {
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                roles.changeRole(target, TenantRole.OPERATOR);
                status.setRollbackOnly();
            });
            assertThat(memberships.findByTenantIdAndIdentityId(context.tenantId(), target).orElseThrow().role()).isEqualTo(TenantRole.VIEWER);
            assertThat(reader.read().events()).isEmpty();
        });
    }

    @Test
    void auditFailureAbortsDenialAndRollsBackSuccessfulStateChange() throws SQLException {
        TenantContext context = tenant(TenantRole.ADMIN);
        IdentityId target = member(context, TenantRole.VIEWER);
        migrationSql("REVOKE INSERT ON dokene.audit_events FROM dokene_runtime");
        try {
            scoped(context, () -> {
                assertThatThrownBy(() -> roles.changeRole(target, TenantRole.OPERATOR)).isInstanceOf(AuditPersistenceException.class);
                assertThat(memberships.findByTenantIdAndIdentityId(context.tenantId(), target).orElseThrow().role()).isEqualTo(TenantRole.VIEWER);
                assertThat(reader.read().events()).isEmpty();
                assertThatThrownBy(() -> authorization.hasPermission(TenantPermission.TENANT_ARCHIVE))
                        .isInstanceOf(AuditPersistenceException.class).hasMessage("Audit persistence unavailable").hasNoCause();
            });
        } finally {
            migrationSql("GRANT INSERT ON dokene.audit_events TO dokene_runtime");
        }
    }

    @Test
    void disallowsSuccessAuditOutsideBusinessTransaction() {
        scoped(tenant(TenantRole.ADMIN), () -> {
            assertThatThrownBy(() -> recorder.membershipRoleChanged(new TenantMembershipId(UUID.randomUUID()),
                    TenantRole.VIEWER, TenantRole.ADMIN)).isInstanceOf(AuditPersistenceException.class);
            assertThat(reader.read().events()).isEmpty();
        });
    }

    @Test
    void rejectsOwnershipInvalidAndForeignMembershipChanges() {
        TenantContext context = tenant(TenantRole.ADMIN);
        IdentityId owner = member(context, TenantRole.OWNER);
        IdentityId viewer = member(context, TenantRole.VIEWER);
        IdentityId foreign = member(tenant(TenantRole.ADMIN), TenantRole.VIEWER);
        scoped(context, () -> {
            assertThatThrownBy(() -> roles.changeRole(owner, TenantRole.VIEWER)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> roles.changeRole(viewer, TenantRole.OWNER)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> roles.changeRole(foreign, TenantRole.ADMIN)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> roles.changeRole(viewer, TenantRole.VIEWER)).isInstanceOf(IllegalStateException.class);
            transaction(() -> {
                TenantMembership member = memberships.findByTenantIdAndIdentityId(context.tenantId(), viewer).orElseThrow();
                member.revoke(Instant.now());
                memberships.save(member);
            });
            assertThatThrownBy(() -> roles.changeRole(viewer, TenantRole.ADMIN)).isInstanceOf(IllegalStateException.class);
            assertThat(reader.read().events()).isEmpty();
        });
        TenantContext denied = tenant(TenantRole.OPERATOR);
        scoped(denied, () -> {
            assertThatThrownBy(() -> roles.changeRole(denied.identityId(), TenantRole.ADMIN)).isInstanceOf(TenantAccessDeniedException.class);
            assertThat(jdbc.queryForObject("SELECT event_type FROM dokene.audit_events", String.class)).isEqualTo("AUTHORIZATION_DENIED");
        });
    }

    @Test
    void paginatesEqualTimestampsWithoutDuplicatesAndValidatesLimits() {
        scoped(tenant(TenantRole.ADMIN), () -> {
            // Fixed database timestamp tests the UUID tie breaker independently from the application clock.
            for (int index = 0; index < 7; index++) {
                insertDenial(contexts.requireCurrent().tenantId(), "UNSPECIFIED", "AUDIT_READ");
            }
            List<UUID> ids = new ArrayList<>();
            AuditPage page = reader.read(null, 2);
            while (true) {
                ids.addAll(page.events().stream().map(AuditEvent::id).toList());
                if (page.nextCursor().isEmpty()) break;
                page = reader.read(page.nextCursor().orElseThrow(), 2);
            }
            assertThat(ids).hasSize(7).doesNotHaveDuplicates();
            assertThat(ids).containsExactlyElementsOf(jdbc.queryForList("SELECT id FROM dokene.audit_events ORDER BY occurred_at DESC, id DESC", UUID.class));
            assertThatThrownBy(() -> reader.read(null, 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> reader.read(null, 101)).isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void concurrentRoleChangesProduceOnlyOneSuccessEvent() throws Exception {
        TenantContext context = tenant(TenantRole.ADMIN);
        IdentityId target = member(context, TenantRole.VIEWER);
        CountDownLatch bothRead = new CountDownLatch(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            bothRead.countDown();
            assertThat(bothRead.await(10, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(memberships).findByTenantIdAndIdentityId(any(), eq(target));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> changeConcurrently(context, target, TenantRole.ADMIN));
            var second = executor.submit(() -> changeConcurrently(context, target, TenantRole.OPERATOR));
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("success", "conflict");
        }
        scoped(context, () -> assertThat(reader.read().events()).hasSize(1));
    }

    private String changeConcurrently(TenantContext context, IdentityId target, TenantRole role) {
        try {
            scoped(context, () -> roles.changeRole(target, role));
            return "success";
        } catch (OptimisticLockingFailureException exception) {
            return "conflict";
        }
    }

    private TenantContext tenant(TenantRole role) {
        TenantContext context = new TenantContext(new TenantId(UUID.randomUUID()), new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), role);
        scoped(context, () -> transaction(() -> {
            tenants.save(Tenant.create(context.tenantId(), "Audit integration", Instant.now().minusSeconds(60)));
            memberships.save(TenantMembership.createActive(context.membershipId(), context.tenantId(), context.identityId(),
                    role, Instant.now().minusSeconds(60)));
        }));
        return context;
    }

    private IdentityId member(TenantContext context, TenantRole role) {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        scoped(context, () -> transaction(() -> memberships.save(TenantMembership.createActive(
                new TenantMembershipId(UUID.randomUUID()), context.tenantId(), identity, role, Instant.now().minusSeconds(60)))));
        return identity;
    }

    private void scoped(TenantContext context, Runnable operation) {
        execution.runWithCorrelation(UUID.randomUUID(), () -> contexts.runWithContext(context, operation));
    }

    private void transaction(Runnable operation) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> operation.run());
    }

    private void insertDenial(TenantId tenant, String reason, String permission) {
        jdbc.update("""
                INSERT INTO dokene.audit_events
                    (id, occurred_at, tenant_id, actor_id, membership_id, event_type, outcome, correlation_id, denial_reason, permission)
                VALUES (?, '2026-09-01T00:00:00Z', ?, ?, ?, 'AUTHORIZATION_DENIED', 'DENIED', ?, ?, ?)
                """, UUID.randomUUID(), tenant.value(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), reason, permission);
    }

    private Connection migration() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), "dokene_migration", MIGRATION_PASSWORD);
    }

    private void migrationSql(String sql) throws SQLException {
        try (Connection connection = migration(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
