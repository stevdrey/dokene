// ABOUTME: Proves V18 and V19 apply on top of the schema main carries today when that schema already holds rows.
// ABOUTME: Drives Flyway by hand in its own database: migrate to V17, seed as the migration role, then upgrade.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class OutboundMessagingSchemaUpgradeIntegrationTest {
    /** The last migration on main before outbound messaging; the upgrade under test starts from here. */
    private static final String SCHEMA_BEFORE_OUTBOUND_MESSAGING = "17";
    private static final List<String> MESSAGING_TABLES = List.of("outbound_messages", "outbound_message_approvals",
            "outbound_message_cancellations", "outbound_send_attempts", "outbound_message_events",
            "outbound_message_idempotency_keys");

    /** covers: issue #133 verification (migration from the current schema succeeds and keeps existing rows). */
    @Test
    void upgradesAPopulatedSchemaAndKeepsItsAuditAndFollowUpRows() throws Exception {
        String url = TenantSecurityIntegrationFixture.createEmptyDatabase(
                "upgrade_" + UUID.randomUUID().toString().replace("-", ""));
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        assertThat(flyway(url, SCHEMA_BEFORE_OUTBOUND_MESSAGING).migrate().success).isTrue();
        try (Connection migration = migrationConnection(url)) {
            seedRowsThatPredateOutboundMessaging(migration, tenantId, customerId);
            assertThat(count(migration, "SELECT count(*) FROM dokene.audit_events")).isEqualTo(3);
        }

        Flyway latest = flyway(url, "latest");
        assertThat(latest.migrate().success).isTrue();
        assertThat(latest.info().pending()).isEmpty();
        assertThat(Integer.parseInt(latest.info().current().getVersion().getVersion()))
                .isGreaterThan(Integer.parseInt(SCHEMA_BEFORE_OUTBOUND_MESSAGING));

        try (Connection migration = migrationConnection(url)) {
            assertThat(count(migration, """
                    SELECT count(*) FROM dokene.audit_events
                    WHERE status_from IS NULL AND status_to IS NULL AND failure_category IS NULL
                        AND attempt_number IS NULL AND enabled IS NULL
                    """)).isEqualTo(3);
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
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.audit_events
                    (id, occurred_at, tenant_id, actor_id, membership_id, event_type, outcome, correlation_id,
                     denial_reason, permission)
                VALUES (gen_random_uuid(), now(), ?, gen_random_uuid(), gen_random_uuid(), 'AUTHORIZATION_DENIED',
                    'DENIED', gen_random_uuid(), 'UNSPECIFIED', 'AUDIT_READ')
                """, tenantId);
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.audit_events
                    (id, occurred_at, tenant_id, actor_id, membership_id, event_type, outcome, correlation_id,
                     denial_reason)
                VALUES (gen_random_uuid(), now(), NULL, NULL, NULL, 'AUTHORIZATION_DENIED', 'DENIED',
                    gen_random_uuid(), 'NO_TENANT_CONTEXT')
                """);
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.audit_events
                    (id, occurred_at, tenant_id, actor_id, membership_id, event_type, target_type, target_id,
                     outcome, correlation_id)
                VALUES (gen_random_uuid(), now(), ?, gen_random_uuid(), gen_random_uuid(), 'CUSTOMER_CREATED',
                    'CUSTOMER', ?, 'SUCCESS', gen_random_uuid())
                """, tenantId, customerId);
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
