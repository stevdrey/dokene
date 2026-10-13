// ABOUTME: Proves the outbound messaging migrations apply on top of the schema before them when it holds rows.
// ABOUTME: Drives Flyway by hand in its own database: stop before them, seed as the migration role, then upgrade.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

class OutboundMessagingSchemaUpgradeIntegrationTest {
    private static final List<String> OUTBOUND_MESSAGING_MIGRATIONS = List.of(
            "create outbound messaging", "extend audit for outbound messaging");
    private static final List<String> MESSAGING_TABLES = List.of("outbound_messages", "outbound_message_approvals",
            "outbound_message_cancellations", "outbound_send_attempts", "outbound_message_events",
            "outbound_message_idempotency_keys");
    private static final int SEEDED_AUDIT_ROWS = 10;

    /** covers: issue #133 verification (migration from the current schema succeeds and keeps existing rows). */
    @Test
    void upgradesAPopulatedSchemaAndKeepsItsAuditAndFollowUpRows() throws Exception {
        String url = TenantSecurityIntegrationFixture.createEmptyDatabase(
                "upgrade_" + UUID.randomUUID().toString().replace("-", ""));
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        assertThat(flyway(url, versionBeforeOutboundMessaging(url)).migrate().success).isTrue();
        Flyway latest = flyway(url, "latest");
        assertThat(latest.info().pending()).extracting(MigrationInfo::getDescription)
                .startsWith(OUTBOUND_MESSAGING_MIGRATIONS.toArray(String[]::new));
        try (Connection migration = migrationConnection(url)) {
            seedRowsThatPredateOutboundMessaging(migration, tenantId, customerId);
            assertThat(count(migration, "SELECT count(*) FROM dokene.audit_events")).isEqualTo(SEEDED_AUDIT_ROWS);
        }

        assertThat(latest.migrate().success).isTrue();
        assertThat(latest.info().pending()).isEmpty();

        try (Connection migration = migrationConnection(url)) {
            assertThat(count(migration, """
                    SELECT count(*) FROM dokene.audit_events
                    WHERE status_from IS NULL AND status_to IS NULL AND failure_category IS NULL
                        AND attempt_number IS NULL AND enabled IS NULL
                    """)).isEqualTo(SEEDED_AUDIT_ROWS);
            assertThat(count(migration, """
                    SELECT count(*) FROM dokene.customer_follow_up_policies
                    WHERE tenant_id = ? AND customer_id = ? AND cadence_days = 45
                        AND explicit_next_date = DATE '2026-11-01' AND last_outbound_message_date IS NULL
                    """, tenantId, customerId)).isEqualTo(1);
            for (String table : MESSAGING_TABLES) {
                assertThat(count(migration, "SELECT count(*) FROM dokene." + table)).as(table).isZero();
            }
            assertThat(count(migration, """
                    SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'dokene' AND p.proname = 'append_audit_event'
                    """)).isEqualTo(1);

            // The swapped attribution constraint takes the provider shape next to the rows that predate it.
            assertThat(TenantSecurityIntegrationFixture.executeUpdate(migration, """
                    INSERT INTO dokene.audit_events
                        (id, occurred_at, tenant_id, actor_id, membership_id, event_type, target_type, target_id,
                         outcome, correlation_id, status_from, status_to)
                    VALUES (gen_random_uuid(), now(), ?, NULL, NULL, 'MESSAGE_DELIVERY_UPDATED', 'MESSAGE',
                        gen_random_uuid(), 'SUCCESS', gen_random_uuid(), 'SENT', 'DELIVERED')
                    """, tenantId)).isEqualTo(1);
        }
    }

    /** Found by description, so the starting point follows the migrations if they are ever renumbered. */
    private static String versionBeforeOutboundMessaging(String url) {
        List<MigrationInfo> all = Arrays.asList(flyway(url, "latest").info().all());
        List<String> descriptions = all.stream().map(MigrationInfo::getDescription).toList();
        int first = descriptions.indexOf(OUTBOUND_MESSAGING_MIGRATIONS.getFirst());
        assertThat(first).isPositive();
        return all.get(first - 1).getVersion().getVersion();
    }

    private static void seedRowsThatPredateOutboundMessaging(Connection migration, UUID tenantId, UUID customerId)
            throws SQLException {
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.tenants (id, display_name, status, created_at, updated_at, version)
                VALUES (?, 'Upgrade tenant', 'ACTIVE', now(), now(), 0)
                """, tenantId);
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.customers (id, tenant_id, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, 'Upgrade customer', 'ACTIVE', now(), now(), 0)
                """, customerId, tenantId);
        // The customers trigger created the default policy row; give it values the upgrade must carry over.
        assertThat(TenantSecurityIntegrationFixture.executeUpdate(migration, """
                UPDATE dokene.customer_follow_up_policies
                SET cadence_days = 45, explicit_next_date = DATE '2026-11-01'
                WHERE tenant_id = ? AND customer_id = ?
                """, tenantId, customerId)).isEqualTo(1);
        // One row per branch of the audit shape check that the upgrade rewrites, so each must still accept its rows.
        assertThat(TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.audit_events
                    (id, occurred_at, tenant_id, actor_id, membership_id, event_type, target_type, target_id,
                     outcome, correlation_id, permission, denial_reason, previous_role, new_role,
                     ai_operation, ai_outcome, ai_detail)
                SELECT gen_random_uuid(), now(), CAST(? AS UUID), gen_random_uuid(), gen_random_uuid(),
                    seed.event_type, seed.target_type,
                    CASE WHEN seed.target_type IS NULL THEN NULL ELSE gen_random_uuid() END,
                    seed.outcome, gen_random_uuid(), seed.permission, seed.denial_reason, seed.previous_role,
                    seed.new_role, seed.ai_operation, seed.ai_outcome, seed.ai_detail
                FROM (VALUES
                    ('AUTHORIZATION_DENIED', NULL, 'DENIED', 'AUDIT_READ', 'UNSPECIFIED', NULL, NULL, NULL, NULL, NULL),
                    ('MEMBERSHIP_ROLE_CHANGED', 'MEMBERSHIP', 'SUCCESS', NULL, NULL, 'ADMIN', 'VIEWER', NULL, NULL, NULL),
                    ('MEMBERSHIP_CREATED', 'MEMBERSHIP', 'SUCCESS', NULL, NULL, NULL, 'OPERATOR', NULL, NULL, NULL),
                    ('MEMBERSHIP_REVOKED', 'MEMBERSHIP', 'SUCCESS', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                    ('CUSTOMER_CREATED', 'CUSTOMER', 'SUCCESS', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                    ('PURCHASE_RECORDED', 'PURCHASE', 'SUCCESS', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                    ('TENANT_FOLLOW_UP_POLICY_CHANGED', 'TENANT', 'SUCCESS', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                    ('FOLLOW_UP_SNOOZED', 'CUSTOMER', 'SUCCESS', NULL, NULL, NULL, NULL, NULL, NULL, NULL),
                    ('AI_INVOCATION_OUTCOME', 'CUSTOMER', 'SUCCESS', NULL, NULL, NULL, NULL,
                        'NEXT_BEST_ACTION', 'GENERATED', 'NONE')
                ) AS seed(event_type, target_type, outcome, permission, denial_reason, previous_role, new_role,
                    ai_operation, ai_outcome, ai_detail)
                """, tenantId)).isEqualTo(SEEDED_AUDIT_ROWS - 1);
        // The tenantless denial has no actor either, so it cannot share the statement above.
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.audit_events
                    (id, occurred_at, tenant_id, actor_id, membership_id, event_type, outcome, correlation_id,
                     denial_reason)
                VALUES (gen_random_uuid(), now(), NULL, NULL, NULL, 'AUTHORIZATION_DENIED', 'DENIED',
                    gen_random_uuid(), 'NO_TENANT_CONTEXT')
                """);
    }

    private static Flyway flyway(String url, String target) {
        return Flyway.configure()
                .dataSource(url, TenantSecurityIntegrationFixture.MIGRATION_ROLE,
                        TenantSecurityIntegrationFixture.MIGRATION_PASSWORD)
                .schemas("dokene")
                .defaultSchema("dokene")
                .createSchemas(true)
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .target(target)
                .load();
    }

    private static Connection migrationConnection(String url) throws SQLException {
        return DriverManager.getConnection(url, TenantSecurityIntegrationFixture.MIGRATION_ROLE,
                TenantSecurityIntegrationFixture.MIGRATION_PASSWORD);
    }

    private static int count(Connection connection, String sql, Object... parameters) throws SQLException {
        return TenantSecurityIntegrationFixture.queryInt(connection, sql, parameters);
    }
}
