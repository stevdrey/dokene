// ABOUTME: Proves the template evidence migrations drop approvals checked under weaker rules: placeholders (issue 218)
// ABOUTME: and the fixed-text rules version (issue 268, V32). Drives Flyway by hand: seed before each, then upgrade.
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

class TemplateSchemaEvidenceUpgradeIntegrationTest {
    private static final String SCHEMA_EVIDENCE_MIGRATION = "verify template parameter schema";
    private static final String RULES_VERSION_MIGRATION = "template content rules version";

    /** covers: issue #218 (no mapping stays sendable on status, name and language alone after the upgrade). */
    @Test
    void approvalsRecordedBeforeTheSchemaCheckReturnToUnverifiedAndOlderWritersStillWork() throws Exception {
        String url = TenantSecurityIntegrationFixture.createEmptyDatabase(
                "upgrade_" + UUID.randomUUID().toString().replace("-", ""));
        UUID tenantId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();

        assertThat(flyway(url, versionBefore(url, SCHEMA_EVIDENCE_MIGRATION)).migrate().success).isTrue();
        try (Connection migration = migrationConnection(url)) {
            seedMappings(migration, tenantId, integrationId);
        }

        Flyway latest = flyway(url, "latest");
        assertThat(latest.migrate().success).isTrue();
        assertThat(latest.info().pending()).isEmpty();

        try (Connection migration = migrationConnection(url)) {
            assertThat(count(migration, """
                    SELECT count(*) FROM dokene.tenant_provider_templates
                    WHERE tenant_id = ? AND semantic_intent = 'REPEAT_PURCHASE' AND approval_status = 'UNVERIFIED'
                        AND approval_checked_at IS NULL AND approval_checked_business_account_id IS NULL
                        AND approval_checked_version IS NULL AND approval_parameter_format IS NULL
                    """, tenantId)).isEqualTo(1);
            assertThat(count(migration, """
                    SELECT count(*) FROM dokene.tenant_provider_templates
                    WHERE tenant_id = ? AND semantic_intent = 'RELATED_PRODUCT' AND approval_status = 'REJECTED'
                        AND approval_checked_at IS NOT NULL AND approval_checked_business_account_id = '400000000000001'
                        AND approval_checked_version = 0 AND approval_parameter_format IS NULL
                    """, tenantId)).isEqualTo(1);
            assertThat(count(migration, "SELECT count(*) FROM dokene.tenant_provider_templates WHERE tenant_id = ?",
                    tenantId)).isEqualTo(2);
            // Expand phase: the previous release's status-only refresh still writes after the upgrade.
            assertThat(TenantSecurityIntegrationFixture.executeUpdate(migration, """
                    UPDATE dokene.tenant_provider_templates
                    SET approval_status = 'APPROVED', approval_checked_at = now(),
                        approval_checked_business_account_id = '400000000000001', approval_checked_version = 0
                    WHERE tenant_id = ? AND semantic_intent = 'REPEAT_PURCHASE'
                    """, tenantId)).isEqualTo(1);
        }
    }

    /**
     * covers: issue #268 (V32). Content safety-checked before the rules version existed was checked under earlier
     * rules, so the approval returns to UNVERIFIED and its content is cleared: it reads as needing a refresh rather
     * than as APPROVED and unusable.
     */
    @Test
    void approvalsRecordedBeforeTheRulesVersionReturnToUnverified() throws Exception {
        String url = TenantSecurityIntegrationFixture.createEmptyDatabase(
                "rules_" + UUID.randomUUID().toString().replace("-", ""));
        UUID tenantId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();

        assertThat(flyway(url, versionBefore(url, RULES_VERSION_MIGRATION)).migrate().success).isTrue();
        try (Connection migration = migrationConnection(url)) {
            seedMappings(migration, tenantId, integrationId);
            // The previous release's approved refresh, with its fixed content (V31).
            assertThat(TenantSecurityIntegrationFixture.executeUpdate(migration, """
                    UPDATE dokene.tenant_provider_templates
                    SET approval_content_parameter_format = 'POSITIONAL', approval_body_text = 'Hola {{1}}',
                        approval_button_types = '{}', approval_button_texts = '{}', approval_button_values = '{}',
                        approval_content_checked_at = approval_checked_at
                    WHERE tenant_id = ? AND semantic_intent = 'REPEAT_PURCHASE'
                    """, tenantId)).isEqualTo(1);
        }

        assertThat(flyway(url, "latest").migrate().success).isTrue();

        try (Connection migration = migrationConnection(url)) {
            assertThat(count(migration, """
                    SELECT count(*) FROM dokene.tenant_provider_templates
                    WHERE tenant_id = ? AND semantic_intent = 'REPEAT_PURCHASE' AND approval_status = 'UNVERIFIED'
                        AND approval_checked_at IS NULL AND approval_body_text IS NULL
                        AND approval_content_checked_at IS NULL AND approval_content_rules_version IS NULL
                    """, tenantId)).isEqualTo(1);
            // Evidence that was never approved keeps its state.
            assertThat(count(migration, """
                    SELECT count(*) FROM dokene.tenant_provider_templates
                    WHERE tenant_id = ? AND semantic_intent = 'RELATED_PRODUCT' AND approval_status = 'REJECTED'
                    """, tenantId)).isEqualTo(1);
        }
    }

    /** Found by description, so the starting point follows the migration if it is ever renumbered. */
    private static String versionBefore(String url, String description) {
        List<MigrationInfo> all = Arrays.asList(flyway(url, "latest").info().all());
        int index = all.stream().map(MigrationInfo::getDescription).toList().indexOf(description);
        assertThat(index).isPositive();
        return all.get(index - 1).getVersion().getVersion();
    }

    private static void seedMappings(Connection migration, UUID tenantId, UUID integrationId) throws SQLException {
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.tenants (id, display_name, status, created_at, updated_at, version)
                VALUES (?, 'Upgrade tenant', 'ACTIVE', now(), now(), 0)
                """, tenantId);
        TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.tenant_messaging_integrations (id, tenant_id, provider, phone_number_id,
                    business_account_id, enabled, secret_ref, created_at, updated_at)
                VALUES (?, ?, 'META_WHATSAPP', '100000000000001', '400000000000001', true, 'wa-token', now(), now())
                """, integrationId, tenantId);
        assertThat(TenantSecurityIntegrationFixture.executeUpdate(migration, """
                INSERT INTO dokene.tenant_provider_templates (id, tenant_id, integration_id, semantic_intent,
                    provider_template_name, language, parameter_names, enabled, approval_status, approval_checked_at,
                    approval_checked_business_account_id, approval_checked_version, created_at, updated_at)
                VALUES (gen_random_uuid(), CAST(? AS UUID), CAST(? AS UUID), 'REPEAT_PURCHASE', 'repeat_purchase_v1',
                        'es_MX', '{customer_name}', true, 'APPROVED', now(), '400000000000001', 0, now(), now()),
                    (gen_random_uuid(), CAST(? AS UUID), CAST(? AS UUID), 'RELATED_PRODUCT', 'related_product_v1',
                        'es', '{}', true, 'REJECTED', now(), '400000000000001', 0, now(), now())
                """, tenantId, integrationId, tenantId, integrationId)).isEqualTo(2);
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
