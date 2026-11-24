// ABOUTME: Proves the denial audit bound against PostgreSQL (spec 0020, issue 310): floods leave ten rows and one
// ABOUTME: summary, counts stay inside their tenant, retries never duplicate, and V40 admits only the three shapes.
package io.github.stevdrey.dokene.audit.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditReader;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.application.DenialAuditBudget;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.AuditOutcome;
import io.github.stevdrey.dokene.audit.persistence.jdbc.TransactionalAuditRecorder.SummaryWrite;
import io.github.stevdrey.dokene.audit.testsupport.MutableClock;
import io.github.stevdrey.dokene.integration.testsupport.ComposeImages;
import io.github.stevdrey.dokene.tenant.application.MembershipService;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// The sweep interval equals the window so the scheduled run never fires during a test; tests call sweepOnce().
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "dokene.audit.denial-limit.rows-per-key=10",
        "dokene.audit.denial-limit.rows-per-identity=60",
        "dokene.audit.denial-limit.sweep-interval=PT15M"})
@Testcontainers
// The container stops with the class: close the context too, or its pool keeps logging against a dead database.
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
class DenialAuditBoundIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(ComposeImages.postgres());
    private static final String MIGRATION_PASSWORD = UUID.randomUUID().toString();
    private static final String RUNTIME_PASSWORD = UUID.randomUUID().toString();
    private static final String SIGNING_KEY = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final MutableClock CLOCK = new MutableClock(Instant.now());
    private static final String SUMMARY = "AUTHORIZATION_DENIALS_SUPPRESSED";
    private static final String SUPPRESSED_LINE = "Authorization denial rows suppressed for this window";

    @TestBean private Clock clock;
    @Autowired private AuditRecorder recorder;
    @Autowired private TransactionalAuditRecorder summaryWriter;
    @Autowired private DenialAuditSummarySweep sweep;
    @Autowired private DenialAuditBudget budget;
    @Autowired private AuditReader reader;
    @Autowired private AuditExecutionContext execution;
    @Autowired private TenantContextProvider contexts;
    @Autowired private TenantAuthorizationService authorization;
    @Autowired private MembershipService membershipService;
    @Autowired private TenantRepository tenants;
    @Autowired private TenantMembershipRepository memberships;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private JdbcAuditStore store;

    static Clock clock() {
        return CLOCK;
    }

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
        registry.add("dokene.database.migrate-on-startup", () -> "true");
        registry.add("dokene.tenant-context.signing-key", () -> SIGNING_KEY);
    }

    /** Ends and writes whatever an earlier test left counted, so each test starts with an empty tracker. */
    @BeforeEach
    void emptyTracker() {
        reset(store);
        CLOCK.advance(WINDOW);
        sweep.sweepOnce();
        assertThat(budget.trackedWindows()).isZero();
    }

    /** covers: AC-1, AC-2, AC-6, AC-8, AC-17 */
    @Test
    void aFloodWithoutATenantLeavesTenRowsAndOneSummaryOf990(CapturedOutput output) {
        IdentityId identity = new IdentityId(UUID.randomUUID());

        List<UUID> correlations = denyWithoutTenant(identity, TenantPermission.TENANT_READ, 1_000);

        assertThat(output.getAll()).containsOnlyOnce(SUPPRESSED_LINE).contains("identity=" + identity.value(),
                "reason=NO_TENANT_CONTEXT", "permission=TENANT_READ", "cause=KEY_BUDGET");
        assertThat(types(correlations)).hasSize(10).containsOnly("AUTHORIZATION_DENIED");
        assertThat(types(correlations.subList(0, 10))).hasSize(10);
        sweep.sweepOnce();
        assertThat(types(correlations)).as("the window is still open").hasSize(10);

        CLOCK.advance(WINDOW);
        sweep.sweepOnce();
        sweep.sweepOnce();

        List<Map<String, Object>> rows = rows(correlations);
        assertThat(rows).hasSize(11);
        List<Map<String, Object>> summaries = rows.stream()
                .filter(row -> SUMMARY.equals(row.get("event_type"))).toList();
        assertThat(summaries).hasSize(1);
        Map<String, Object> summary = summaries.getFirst();
        assertThat(summary.get("suppressed_count")).isEqualTo(990);
        assertThat(summary.get("permission")).isEqualTo("TENANT_READ");
        assertThat(summary.get("denial_reason")).isEqualTo("NO_TENANT_CONTEXT");
        assertThat(summary.get("outcome")).isEqualTo("DENIED");
        assertThat(summary.get("correlation_id")).isEqualTo(correlations.get(10));
        assertThat(summary.get("tenant_id")).isNull();
        assertThat(summary.get("actor_id")).isNull();
        assertThat(summary.get("membership_id")).isNull();
        assertThat(summary.get("target_type")).isNull();
        // Unattributed rows and counts stay invisible to the runtime role, as unattributed denials always were.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dokene.audit_events WHERE correlation_id = ?",
                Integer.class, correlations.get(10))).isZero();
    }

    /** covers: AC-3, AC-14 */
    @Test
    void aFloodInsideATenantIsSummarizedWithItsMemberEvenAfterThatMemberIsRevoked(CapturedOutput output) {
        TenantContext owner = tenant();
        TenantContext viewer = member(owner, new IdentityId(UUID.randomUUID()), TenantRole.VIEWER);
        TenantContext otherTenant = tenant();

        for (int denial = 0; denial < 30; denial++) {
            scoped(viewer, () -> recorder.authorizationDenied(
                    TenantPermission.CUSTOMER_WRITE, AuditDenialReason.INSUFFICIENT_PERMISSION));
        }
        // A spent budget never changes the refusal itself: the real authorization path still denies every time,
        // and its denials are charged to the same key, so they are counted too.
        for (int denial = 0; denial < 25; denial++) {
            scoped(viewer, () -> assertThatThrownBy(
                    () -> authorization.requirePermission(TenantPermission.CUSTOMER_WRITE))
                    .isInstanceOf(TenantAccessDeniedException.class));
        }
        assertThat(output.getAll()).containsOnlyOnce(SUPPRESSED_LINE).contains(
                "identity=" + viewer.identityId().value(), "reason=INSUFFICIENT_PERMISSION",
                "permission=CUSTOMER_WRITE", "cause=KEY_BUDGET");
        scoped(owner, () -> membershipService.revokeMembership(viewer.identityId()));
        CLOCK.advance(WINDOW);
        sweep.sweepOnce();

        List<AuditEvent> events = read(owner);
        assertThat(events).filteredOn(event -> event.type() == AuditEventType.AUTHORIZATION_DENIED
                && event.metadata().equals(new AuditMetadata.AuthorizationDenied(
                        TenantPermission.CUSTOMER_WRITE, AuditDenialReason.INSUFFICIENT_PERMISSION))).hasSize(10);
        List<AuditEvent> summaries = events.stream()
                .filter(event -> event.type() == AuditEventType.AUTHORIZATION_DENIALS_SUPPRESSED).toList();
        assertThat(summaries).hasSize(1).allSatisfy(event -> {
            assertThat(event.tenantId()).isEqualTo(owner.tenantId());
            assertThat(event.actorId()).isEqualTo(viewer.identityId());
            assertThat(event.membershipId()).isEqualTo(viewer.membershipId());
            assertThat(event.outcome()).isEqualTo(AuditOutcome.DENIED);
            assertThat(event.target()).isNull();
        });
        assertThat(summaries.getFirst().metadata()).isEqualTo(new AuditMetadata.AuthorizationDenialsSuppressed(
                TenantPermission.CUSTOMER_WRITE, AuditDenialReason.INSUFFICIENT_PERMISSION, 45));
        assertThat(read(otherTenant)).noneMatch(
                event -> event.type() == AuditEventType.AUTHORIZATION_DENIALS_SUPPRESSED);
    }

    /** covers: AC-4, AC-5, AC-14 */
    @Test
    void aMemberRefusedOnSevenPermissionsWritesSixtyRowsAndTheRestAreCountedUnderEachPermission(
            CapturedOutput output) {
        TenantContext owner = tenant();
        TenantContext viewer = member(owner, new IdentityId(UUID.randomUUID()), TenantRole.VIEWER);
        TenantContext secondViewer = member(owner, new IdentityId(UUID.randomUUID()), TenantRole.VIEWER);
        List<TenantPermission> refused = List.of(TenantPermission.CUSTOMER_WRITE, TenantPermission.CUSTOMER_DELETE,
                TenantPermission.PURCHASE_WRITE, TenantPermission.INTEGRATION_MANAGE, TenantPermission.TENANT_UPDATE,
                TenantPermission.MESSAGE_SEND, TenantPermission.MEMBERSHIP_INVITE);

        // The real authorization path, twelve times per permission: every one is refused whatever the budget says.
        for (TenantPermission permission : refused) {
            for (int denial = 0; denial < 12; denial++) {
                scoped(viewer, () -> assertThatThrownBy(() -> authorization.requirePermission(permission))
                        .isInstanceOf(TenantAccessDeniedException.class));
            }
        }
        scoped(secondViewer, () -> assertThatThrownBy(
                () -> authorization.requirePermission(TenantPermission.MEMBERSHIP_INVITE))
                .isInstanceOf(TenantAccessDeniedException.class));

        // One line per key window: six keys ran out of their own budget, the seventh met the identity ceiling.
        assertThat(output.getAll().split(SUPPRESSED_LINE, -1)).hasSize(8);
        assertThat(output.getAll()).containsOnlyOnce("cause=IDENTITY_CEILING")
                .contains("permission=MEMBERSHIP_INVITE, cause=IDENTITY_CEILING");
        List<AuditEvent> beforeTheSweep = read(owner);
        assertThat(denialRows(beforeTheSweep, viewer.identityId())).isEqualTo(60);
        assertThat(beforeTheSweep).filteredOn(event -> event.type() == AuditEventType.AUTHORIZATION_DENIED
                && viewer.identityId().equals(event.actorId()))
                .extracting(event -> ((AuditMetadata.AuthorizationDenied) event.metadata()).permission())
                .as("the seventh permission got no row of its own")
                .doesNotContain(TenantPermission.MEMBERSHIP_INVITE);
        assertThat(denialRows(beforeTheSweep, secondViewer.identityId()))
                .as("another member of the same tenant keeps a full budget").isEqualTo(1);

        CLOCK.advance(WINDOW);
        sweep.sweepOnce();

        List<AuditMetadata> expected = new ArrayList<>();
        for (TenantPermission permission : refused) {
            expected.add(new AuditMetadata.AuthorizationDenialsSuppressed(permission,
                    AuditDenialReason.INSUFFICIENT_PERMISSION, permission == TenantPermission.MEMBERSHIP_INVITE ? 12 : 2));
        }
        assertThat(counts(read(owner))).containsExactlyInAnyOrderElementsOf(expected);
    }

    /** covers: AC-7 */
    @Test
    void theSameIdentityGetsItsOwnRowsAndItsOwnCountInEachTenant(CapturedOutput output) {
        IdentityId identity = new IdentityId(UUID.randomUUID());
        TenantContext firstOwner = tenant();
        TenantContext secondOwner = tenant();
        TenantContext inFirst = member(firstOwner, identity, TenantRole.VIEWER);
        TenantContext inSecond = member(secondOwner, identity, TenantRole.VIEWER);

        denyInTenant(inFirst, TenantPermission.PURCHASE_WRITE, 15);
        denyInTenant(inSecond, TenantPermission.PURCHASE_WRITE, 12);
        assertThat(output.getAll().split(SUPPRESSED_LINE, -1)).as("one line per tenant key").hasSize(3);
        CLOCK.advance(WINDOW);
        sweep.sweepOnce();

        assertThat(denialRows(read(firstOwner), identity)).isEqualTo(10);
        assertThat(denialRows(read(secondOwner), identity)).isEqualTo(10);
        assertThat(counts(read(firstOwner))).containsExactly(new AuditMetadata.AuthorizationDenialsSuppressed(
                TenantPermission.PURCHASE_WRITE, AuditDenialReason.INSUFFICIENT_PERMISSION, 5));
        assertThat(counts(read(secondOwner))).containsExactly(new AuditMetadata.AuthorizationDenialsSuppressed(
                TenantPermission.PURCHASE_WRITE, AuditDenialReason.INSUFFICIENT_PERMISSION, 2));
    }

    /** covers: AC-16 */
    @Test
    void denialsWithoutAPermissionAreSummarizedWithANullPermissionWithAndWithoutATenant(CapturedOutput output) {
        TenantContext owner = tenant();
        TenantContext viewer = member(owner, new IdentityId(UUID.randomUUID()), TenantRole.VIEWER);

        denyInTenant(viewer, null, 12);
        List<UUID> correlations = denyWithoutTenant(new IdentityId(UUID.randomUUID()), null, 13);
        assertThat(output.getAll().split(SUPPRESSED_LINE, -1)).hasSize(3);
        assertThat(output.getAll()).contains("reason=INSUFFICIENT_PERMISSION, permission=null",
                "reason=NO_TENANT_CONTEXT, permission=null");
        CLOCK.advance(WINDOW);
        sweep.sweepOnce();

        assertThat(counts(read(owner))).containsExactly(new AuditMetadata.AuthorizationDenialsSuppressed(
                null, AuditDenialReason.INSUFFICIENT_PERMISSION, 2));
        Map<String, Object> unattributed = rows(correlations).stream()
                .filter(row -> SUMMARY.equals(row.get("event_type"))).findFirst().orElseThrow();
        assertThat(unattributed.get("permission")).isNull();
        assertThat(unattributed.get("denial_reason")).isEqualTo("NO_TENANT_CONTEXT");
        assertThat(unattributed.get("suppressed_count")).isEqualTo(3);
    }

    /** covers: AC-9 */
    @Test
    void anOutageDuringTheSweepIsRetriedAndEndsAsExactlyOneRow(CapturedOutput output) {
        List<UUID> older = denyWithoutTenant(new IdentityId(UUID.randomUUID()), TenantPermission.TENANT_READ, 12);
        CLOCK.advance(Duration.ofSeconds(1));
        List<UUID> newer = denyWithoutTenant(new IdentityId(UUID.randomUUID()), TenantPermission.TENANT_READ, 14);
        CLOCK.advance(WINDOW);
        doThrow(new CannotGetJdbcConnectionException("down")).doThrow(new CannotGetJdbcConnectionException("down"))
                .doCallRealMethod().when(store).append(
                        argThat(event -> event.type() == AuditEventType.AUTHORIZATION_DENIALS_SUPPRESSED), any());

        sweep.sweepOnce();
        sweep.sweepOnce();

        assertThat(types(older)).doesNotContain(SUMMARY);
        assertThat(types(newer)).as("a later window is not touched while the oldest cannot be written")
                .doesNotContain(SUMMARY);
        assertThat(budget.trackedWindows()).isEqualTo(2);
        assertThat(output.getAll().split("Denial suppression summary kept for the next sweep", -1)).hasSize(3);
        assertThat(output.getAll()).contains("Denial suppression summary was not written: retry=true, "
                + "exceptionClass=org.springframework.jdbc.CannotGetJdbcConnectionException");
        assertThat(output.getAll().split(SUPPRESSED_LINE, -1)).hasSize(3);

        sweep.sweepOnce();
        sweep.sweepOnce();

        assertThat(types(older)).filteredOn(SUMMARY::equals).hasSize(1);
        assertThat(types(newer)).filteredOn(SUMMARY::equals).hasSize(1);
        assertThat(budget.trackedWindows()).isZero();
    }

    /** covers: AC-9 */
    @Test
    void aCountWhoseWriteCommittedIsNotWrittenTwice(CapturedOutput output) {
        List<UUID> correlations = denyWithoutTenant(
                new IdentityId(UUID.randomUUID()), TenantPermission.TENANT_READ, 12);
        assertThat(output.getAll()).containsOnlyOnce(SUPPRESSED_LINE);
        CLOCK.advance(WINDOW);
        DenialAuditBudget.Summary pending = budget.drainEnded().pending().getFirst();

        assertThat(summaryWriter.writeSuppressionSummary(pending)).isEqualTo(SummaryWrite.WRITTEN);
        // The sweep never learned of that commit, so it tries the same row id again.
        assertThat(summaryWriter.writeSuppressionSummary(pending)).isEqualTo(SummaryWrite.ALREADY_WRITTEN);
        sweep.sweepOnce();

        assertThat(types(correlations)).filteredOn(SUMMARY::equals).hasSize(1);
        assertThat(budget.trackedWindows()).isZero();
    }

    /** covers: AC-9 */
    @Test
    void aCountTheDatabaseRejectsIsDroppedAndTheNextOneIsStillWritten(CapturedOutput output) {
        List<UUID> rejected = denyWithoutTenant(new IdentityId(UUID.randomUUID()), TenantPermission.TENANT_READ, 12);
        CLOCK.advance(Duration.ofSeconds(1));
        List<UUID> accepted = denyWithoutTenant(new IdentityId(UUID.randomUUID()), TenantPermission.TENANT_READ, 12);
        CLOCK.advance(WINDOW);
        // What the driver reports when the append function refuses a write with SQLSTATE 28000.
        doThrow(new PermissionDeniedDataAccessException("refused", null))
                .doCallRealMethod().when(store).append(
                        argThat(event -> event.type() == AuditEventType.AUTHORIZATION_DENIALS_SUPPRESSED), any());

        sweep.sweepOnce();

        assertThat(types(rejected)).doesNotContain(SUMMARY);
        assertThat(types(accepted)).filteredOn(SUMMARY::equals).hasSize(1);
        assertThat(budget.trackedWindows()).isZero();
        assertThat(output.getAll().split(SUPPRESSED_LINE, -1)).hasSize(3);
        assertThat(output.getAll()).containsOnlyOnce("Denial suppression summary was not written: retry=false, "
                + "exceptionClass=org.springframework.dao.PermissionDeniedDataAccessException")
                .containsOnlyOnce("Denial suppression summary rejected and dropped: reason=NO_TENANT_CONTEXT, "
                        + "permission=TENANT_READ, suppressedCount=2");
    }

    /** covers: AC-16 */
    @Test
    void theAppendFunctionAdmitsTheUnattributedShapesAndTheOldArgumentList() {
        UUID withPermission = append(SUMMARY, "DENIED", "TENANT_READ", "NO_TENANT_CONTEXT", 5, "");
        UUID withoutPermission = append(SUMMARY, "DENIED", null, "NO_TENANT_CONTEXT", 1, "");
        UUID overflow = append(SUMMARY, "DENIED", null, null, 7, "");
        UUID oldList = UUID.randomUUID();
        jdbc.queryForObject("""
                SELECT dokene.append_audit_event(?, now(), 'AUTHORIZATION_DENIED', NULL, NULL, 'DENIED', ?,
                    'TENANT_READ', 'NO_TENANT_CONTEXT', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
                    NULL, NULL)
                """, UUID.class, oldList, UUID.randomUUID());

        assertThat(migration().queryForObject("""
                SELECT count(*) FROM dokene.audit_events WHERE id IN (?, ?, ?, ?) AND tenant_id IS NULL
                """, Integer.class, withPermission, withoutPermission, overflow, oldList)).isEqualTo(4);
        assertThat(migration().queryForObject(
                "SELECT suppressed_count FROM dokene.audit_events WHERE id = ?", Integer.class, overflow))
                .isEqualTo(7);
        assertThat(migration().queryForObject(
                "SELECT suppressed_count FROM dokene.audit_events WHERE id = ?", Integer.class, oldList)).isNull();
    }

    /** covers: AC-16 */
    @Test
    void theAppendFunctionAndTheConstraintsRejectEveryOtherUseOfACount() {
        assertRejected(() -> append(SUMMARY, "DENIED", "TENANT_READ", "NO_TENANT_CONTEXT", null, ""));
        assertRejected(() -> append(SUMMARY, "DENIED", "TENANT_READ", "NO_TENANT_CONTEXT", 0, ""));
        assertRejected(() -> append(SUMMARY, "DENIED", "TENANT_READ", "NO_TENANT_CONTEXT", -3, ""));
        assertRejected(() -> append("AUTHORIZATION_DENIED", "DENIED", "TENANT_READ", "NO_TENANT_CONTEXT", 5, ""));
        assertRejected(() -> append(SUMMARY, "DENIED", "TENANT_READ", null, 5, ""));
        assertRejected(() -> append(SUMMARY, "SUCCESS", "TENANT_READ", "NO_TENANT_CONTEXT", 5, ""));
        assertRejected(() -> append(SUMMARY, "DENIED", "TENANT_READ", "UNSPECIFIED", 5, ""));
        assertRejected(() -> append(SUMMARY, "DENIED", "NOT_A_PERMISSION", "NO_TENANT_CONTEXT", 5, ""));
        assertRejected(() -> append(null, "DENIED", null, null, 5, ""));
        assertRejected(() -> append("CUSTOMER_CREATED", "DENIED", null, null, null, ""));
        assertRejected(() -> append(SUMMARY, "DENIED", null, null, 5, ", p_enabled => true"));
        assertRejected(() -> append(SUMMARY, "DENIED", null, null, 5, ", p_status_to => 'SENT'"));
        assertRejected(() -> append(SUMMARY, "DENIED", null, null, 5, ", p_failure_category => 'UNKNOWN'"));
        assertRejected(() -> append(SUMMARY, "DENIED", null, null, 5, ", p_attempt_number => 1"));
        assertRejected(() -> jdbc.queryForObject("""
                SELECT dokene.append_audit_event(?, now(), 'AUTHORIZATION_DENIALS_SUPPRESSED', 'CUSTOMER', ?,
                    'DENIED', ?, NULL, NULL, NULL, NULL, p_suppressed_count => 5)
                """, UUID.class, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        // The table constraints hold whoever writes: the owner cannot store a malformed count either.
        TenantId tenant = tenant().tenantId();
        assertRejected(() -> ownerInsertsSummary(tenant, "NO_TENANT_CONTEXT", "TENANT_READ", 5));
        assertRejected(() -> ownerInsertsSummary(tenant, null, null, 5));
        assertRejected(() -> ownerInsertsSummary(null, "INSUFFICIENT_PERMISSION", "TENANT_READ", 5));
        assertRejected(() -> ownerInsertsSummary(null, null, "TENANT_READ", 5));
        assertRejected(() -> ownerInsertsSummary(tenant, "INSUFFICIENT_PERMISSION", "TENANT_READ", null));
        ownerInsertsSummary(tenant, "INSUFFICIENT_PERMISSION", null, 1);

        // The runtime role still has no direct write of any kind.
        assertRejected(() -> jdbc.update("""
                INSERT INTO dokene.audit_events (id, occurred_at, event_type, outcome, correlation_id, suppressed_count)
                VALUES (?, now(), 'AUTHORIZATION_DENIALS_SUPPRESSED', 'DENIED', ?, 5)
                """, UUID.randomUUID(), UUID.randomUUID()));
        assertRejected(() -> jdbc.update("UPDATE dokene.audit_events SET suppressed_count = 1"));
        assertRejected(() -> jdbc.update("DELETE FROM dokene.audit_events"));
    }

    private static void assertRejected(Runnable write) {
        assertThatThrownBy(write::run).isInstanceOf(DataAccessException.class);
    }

    /** Calls the append function as the runtime role with no capability; {@code extra} adds named arguments. */
    private UUID append(String type, String outcome, String permission, String reason, Integer count, String extra) {
        UUID id = UUID.randomUUID();
        jdbc.queryForObject("""
                SELECT dokene.append_audit_event(?, now(), ?::varchar, NULL, NULL, ?::varchar, ?, ?::varchar,
                    ?::varchar, NULL, NULL, p_suppressed_count => ?::integer%s)
                """.formatted(extra), UUID.class, id, type, outcome, UUID.randomUUID(), permission, reason, count);
        return id;
    }

    private void ownerInsertsSummary(TenantId tenant, String reason, String permission, Integer count) {
        migration().update("""
                INSERT INTO dokene.audit_events (id, occurred_at, tenant_id, actor_id, membership_id, event_type,
                    outcome, correlation_id, denial_reason, permission, suppressed_count)
                VALUES (?, now(), ?, ?, ?, 'AUTHORIZATION_DENIALS_SUPPRESSED', 'DENIED', ?, ?, ?, ?)
                """, UUID.randomUUID(), tenant == null ? null : tenant.value(),
                tenant == null ? null : UUID.randomUUID(), tenant == null ? null : UUID.randomUUID(),
                UUID.randomUUID(), reason, permission, count);
    }

    private List<UUID> denyWithoutTenant(IdentityId identity, TenantPermission permission, int denials) {
        List<UUID> correlations = new ArrayList<>();
        for (int denial = 0; denial < denials; denial++) {
            UUID correlation = UUID.randomUUID();
            correlations.add(correlation);
            execution.runWithCorrelation(correlation, () -> recorder.authorizationDenied(
                    permission, AuditDenialReason.NO_TENANT_CONTEXT, identity));
        }
        return correlations;
    }

    private void denyInTenant(TenantContext context, TenantPermission permission, int denials) {
        for (int denial = 0; denial < denials; denial++) {
            scoped(context, () -> recorder.authorizationDenied(permission, AuditDenialReason.INSUFFICIENT_PERMISSION));
        }
    }

    private static long denialRows(List<AuditEvent> events, IdentityId actor) {
        return events.stream().filter(event -> event.type() == AuditEventType.AUTHORIZATION_DENIED
                && actor.equals(event.actorId())).count();
    }

    private static List<AuditMetadata> counts(List<AuditEvent> events) {
        return events.stream().filter(event -> event.type() == AuditEventType.AUTHORIZATION_DENIALS_SUPPRESSED)
                .map(AuditEvent::metadata).toList();
    }

    private List<AuditEvent> read(TenantContext context) {
        List<AuditEvent> events = new ArrayList<>();
        scoped(context, () -> events.addAll(reader.read(null, 100).events()));
        return events;
    }

    private List<String> types(List<UUID> correlations) {
        return rows(correlations).stream().map(row -> (String) row.get("event_type")).toList();
    }

    /** Every audit row of these requests, read as the table owner because unattributed rows are hidden from runtime. */
    private List<Map<String, Object>> rows(List<UUID> correlations) {
        String ids = correlations.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return migration().queryForList(
                "SELECT * FROM dokene.audit_events WHERE correlation_id = ANY (?::uuid[])", ids);
    }

    private JdbcTemplate migration() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "dokene_migration", MIGRATION_PASSWORD));
    }

    private TenantContext tenant() {
        TenantContext context = new TenantContext(new TenantId(UUID.randomUUID()), new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.OWNER);
        scoped(context, () -> transaction(() -> {
            tenants.save(Tenant.create(context.tenantId(), "Denial bound", Instant.now().minusSeconds(60)));
            memberships.save(TenantMembership.createActive(context.membershipId(), context.tenantId(),
                    context.identityId(), TenantRole.OWNER, Instant.now().minusSeconds(60)));
        }));
        return context;
    }

    private TenantContext member(TenantContext owner, IdentityId identity, TenantRole role) {
        TenantContext context = new TenantContext(
                owner.tenantId(), identity, new TenantMembershipId(UUID.randomUUID()), role);
        scoped(owner, () -> transaction(() -> memberships.save(TenantMembership.createActive(
                context.membershipId(), context.tenantId(), identity, role, Instant.now().minusSeconds(60)))));
        return context;
    }

    private void scoped(TenantContext context, Runnable operation) {
        execution.runWithCorrelation(UUID.randomUUID(), () -> contexts.runWithContext(context, operation));
    }

    private void transaction(Runnable operation) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> operation.run());
    }
}
