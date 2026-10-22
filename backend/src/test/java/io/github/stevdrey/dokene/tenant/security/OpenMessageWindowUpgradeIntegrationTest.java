// ABOUTME: Proves V30 narrows the one open message per customer index on a schema that holds a message in every status.
// ABOUTME: Drives Flyway by hand in its own database: stop before V30, seed as the migration role, then upgrade.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

class OpenMessageWindowUpgradeIntegrationTest {
    private static final String NARROWING_MIGRATION = "narrow open message window";
    private static final String UNIQUE_VIOLATION = "23505";

    /** covers: AC-5 and AC-2 of spec 0013 (the index swap applies with rows in every status and still refuses). */
    @Test
    void narrowsTheIndexOverAMessageInEveryStatus() throws Exception {
        String url = TenantSecurityIntegrationFixture.createEmptyDatabase(
                "open_window_" + UUID.randomUUID().toString().replace("-", ""));
        UUID tenantId = UUID.randomUUID();
        Map<MessageStatus, UUID> customerByStatus = new EnumMap<>(MessageStatus.class);

        assertThat(flyway(url, versionBeforeNarrowing(url)).migrate().success).isTrue();
        Flyway latest = flyway(url, "latest");
        assertThat(latest.info().pending()).extracting(MigrationInfo::getDescription).first()
                .isEqualTo(NARROWING_MIGRATION);
        try (Connection migration = migrationConnection(url)) {
            seedTenant(migration, tenantId);
            for (MessageStatus status : MessageStatus.values()) {
                UUID customerId = seedCustomer(migration, tenantId);
                customerByStatus.put(status, customerId);
                assertThat(insertMessage(migration, tenantId, customerId, status)).as(status.name()).isEqualTo(1);
            }
            // Before V30 a sent message still holds its customer.
            assertRefused(migration, tenantId, customerByStatus.get(MessageStatus.SENT));
        }

        assertThat(latest.migrate().success).isTrue();
        assertThat(latest.info().pending()).isEmpty();

        try (Connection migration = migrationConnection(url)) {
            for (MessageStatus status : MessageStatus.values()) {
                assertThat(count(migration, """
                        SELECT count(*) FROM dokene.outbound_messages
                        WHERE tenant_id = ? AND customer_id = ? AND status = ? AND version = 0
                        """, tenantId, customerByStatus.get(status), status.name())).as(status.name()).isEqualTo(1);
            }
            for (MessageStatus status : MessageStatus.values()) {
                UUID customerId = customerByStatus.get(status);
                if (status.open()) {
                    assertRefused(migration, tenantId, customerId);
                } else {
                    assertThat(insertMessage(migration, tenantId, customerId, MessageStatus.PENDING_APPROVAL))
                            .as(status.name()).isEqualTo(1);
                }
            }
        }
    }

    /** Found by description, so the starting point follows the migration if it is ever renumbered. */
    private static String versionBeforeNarrowing(String url) {
        List<MigrationInfo> all = Arrays.asList(flyway(url, "latest").info().all());
        int narrowing = all.stream().map(MigrationInfo::getDescription).toList().indexOf(NARROWING_MIGRATION);
        assertThat(narrowing).isPositive();
        return all.get(narrowing - 1).getVersion().getVersion();
    }

    private static void assertRefused(Connection migration, UUID tenantId, UUID customerId) {
        assertThatThrownBy(() -> insertMessage(migration, tenantId, customerId, MessageStatus.PENDING_APPROVAL))
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(UNIQUE_VIOLATION))
                .hasMessageContaining("one_open_message_per_customer");
    }

    private static void seedTenant(Connection migration, UUID tenantId) throws SQLException {
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.tenants (id, display_name, status, created_at, updated_at, version)
                VALUES (?, 'Open window tenant', 'ACTIVE', now(), now(), 0)
                """, tenantId);
    }

    private static UUID seedCustomer(Connection migration, UUID tenantId) throws SQLException {
        UUID customerId = UUID.randomUUID();
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.customers (id, tenant_id, display_name, status, created_at, updated_at, version)
                VALUES (?, ?, 'Open window customer', 'ACTIVE', now(), now(), 0)
                """, customerId, tenantId);
        return customerId;
    }

    /** Fills only the columns each status's check constraints require: a provider id once sent, a failure category. */
    private static int insertMessage(Connection migration, UUID tenantId, UUID customerId, MessageStatus status)
            throws SQLException {
        boolean accepted = status == MessageStatus.SENT || status == MessageStatus.DELIVERED
                || status == MessageStatus.READ;
        return TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.outbound_messages
                    (id, tenant_id, customer_id, contact_id, recipient_phone, channel, status, action,
                     template_intent, locale, origin, body, source_policy_version, send_key, provider_message_id,
                     failure_category, sent_at, created_by_membership_id, created_by_actor_id, created_at,
                     updated_at)
                VALUES (gen_random_uuid(), ?, ?, gen_random_uuid(), '+50688880000', 'WHATSAPP', ?,
                    'GENERAL_CHECK_IN', 'GENERAL_FOLLOW_UP', 'es-419', 'MANUAL', 'Hola', 0, gen_random_uuid(),
                    CASE WHEN ? THEN 'wamid.SEED-' || gen_random_uuid() END,
                    CASE WHEN ? THEN 'UNKNOWN' END,
                    CASE WHEN ? THEN now() END,
                    gen_random_uuid(), gen_random_uuid(), now(), now())
                """, tenantId, customerId, status.name(), accepted, status == MessageStatus.FAILED, accepted);
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
