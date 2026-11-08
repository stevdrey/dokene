// ABOUTME: Proves tenant-context signing-key retirement (issue 324, audit L6): a superseded key keeps verifying during
// ABOUTME: the grace period of a rolling deployment, stops at its deadline, and a retired key id is never reactivated.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.persistence.jpa.HmacDatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.persistence.jpa.TenantContextSigningKeyCallback;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SigningKeyRetirementIntegrationTest {
    private static final Duration GRACE = Duration.ofHours(1);
    private static final TenantId TENANT = new TenantId(UUID.randomUUID());

    private final String firstKey = randomKey();
    private final String secondKey = randomKey();
    private String url;

    @BeforeEach
    void emptyDatabase() throws SQLException {
        url = TenantSecurityIntegrationFixture.createEmptyDatabase(
                "key_retirement_" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Test
    void rollingRotationKeepsBothKeysValidUntilTheGraceEndsThenRetiresTheOldOne() throws Exception {
        deploy("k1", firstKey, GRACE);
        HmacDatabaseContextSigner oldInstance = new HmacDatabaseContextSigner(firstKey, "k1");
        assertThat(verifiedTenant(oldInstance.issueTenantContext(TENANT))).isEqualTo(TENANT.value());

        deploy("k2", secondKey, GRACE);
        HmacDatabaseContextSigner newInstance = new HmacDatabaseContextSigner(secondKey, "k2");
        // Rolling deployment: instances still signing with k1 and new ones signing with k2 both verify.
        assertThat(verifiedTenant(oldInstance.issueTenantContext(TENANT))).isEqualTo(TENANT.value());
        assertThat(verifiedTenant(newInstance.issueTenantContext(TENANT))).isEqualTo(TENANT.value());
        assertThat(auditKeyCheck(oldInstance.issueProviderAuditContext(TENANT))).isEqualTo(KeyCheck.ACCEPTED);
        KeyRow k1 = row("k1");
        assertThat(k1.status()).isEqualTo("ACTIVE");
        assertThat(k1.retireAfter()).isBetween(databaseNow().plus(GRACE).minusSeconds(60), databaseNow().plus(GRACE));
        assertThat(row("k2").retireAfter()).isNull();

        // The grace ends: the verifiers enforce the deadline on the database clock, without another job run.
        moveDeadlineIntoThePast("k1");
        assertThat(verifiedTenant(oldInstance.issueTenantContext(TENANT))).isNull();
        assertThat(auditKeyCheck(oldInstance.issueProviderAuditContext(TENANT))).isEqualTo(KeyCheck.KEY_REJECTED);
        assertThat(verifiedTenant(newInstance.issueTenantContext(TENANT))).isEqualTo(TENANT.value());
        assertThat(auditKeyCheck(newInstance.issueProviderAuditContext(TENANT))).isEqualTo(KeyCheck.ACCEPTED);
        assertThat(row("k1").status()).as("not yet recorded").isEqualTo("ACTIVE");

        // The next job run records the retirement at the moment the key stopped verifying.
        deploy("k2", secondKey, GRACE);
        KeyRow retired = row("k1");
        assertThat(retired.status()).isEqualTo("RETIRED");
        assertThat(retired.retiredAt()).isEqualTo(retired.retireAfter());
        assertThat(verifiedTenant(oldInstance.issueTenantContext(TENANT))).isNull();
    }

    @Test
    void theOldKeyStopsVerifyingWhenAShortGraceElapsesInRealTime() throws Exception {
        deploy("k1", firstKey, GRACE);
        HmacDatabaseContextSigner oldInstance = new HmacDatabaseContextSigner(firstKey, "k1");
        deploy("k2", secondKey, Duration.ofSeconds(3));
        assertThat(verifiedTenant(oldInstance.issueTenantContext(TENANT))).isEqualTo(TENANT.value());

        Instant giveUp = Instant.now().plusSeconds(15);
        UUID verified = TENANT.value();
        while (verified != null && Instant.now().isBefore(giveUp)) {
            Thread.sleep(250);
            verified = verifiedTenant(oldInstance.issueTenantContext(TENANT));
        }
        assertThat(verified).isNull();
    }

    @Test
    void reRunningTheJobNeverExtendsTheGraceButAShorterGraceRevokesAtOnce() throws Exception {
        deploy("k1", firstKey, GRACE);
        deploy("k2", secondKey, GRACE);
        Instant deadline = row("k1").retireAfter();

        deploy("k2", secondKey, GRACE);
        deploy("k2", secondKey, Duration.ofDays(7));
        assertThat(row("k1").retireAfter()).isEqualTo(deadline);

        // Emergency revocation of a leaked key: re-run the job with a zero grace.
        deploy("k2", secondKey, Duration.ZERO);
        assertThat(row("k1").status()).isEqualTo("RETIRED");
        assertThat(verifiedTenant(new HmacDatabaseContextSigner(firstKey, "k1").issueTenantContext(TENANT))).isNull();
        assertThat(verifiedTenant(new HmacDatabaseContextSigner(secondKey, "k2").issueTenantContext(TENANT)))
                .isEqualTo(TENANT.value());
    }

    @Test
    void rollingBackWithinTheGraceReactivatesThePreviousKey() throws Exception {
        deploy("k1", firstKey, GRACE);
        deploy("k2", secondKey, GRACE);

        deploy("k1", firstKey, GRACE);

        assertThat(row("k1").status()).isEqualTo("ACTIVE");
        assertThat(row("k1").retireAfter()).isNull();
        assertThat(row("k2").retireAfter()).isNotNull();
        assertThat(verifiedTenant(new HmacDatabaseContextSigner(firstKey, "k1").issueTenantContext(TENANT)))
                .isEqualTo(TENANT.value());
        assertThat(verifiedTenant(new HmacDatabaseContextSigner(secondKey, "k2").issueTenantContext(TENANT)))
                .as("instances already on k2 keep working while the rollback rolls out").isEqualTo(TENANT.value());
    }

    @Test
    void rollingBackToARetiredKeyFailsTheJobWithoutChangesOrKeyMaterialInTheError() throws Exception {
        deploy("k1", firstKey, GRACE);
        deploy("k2", secondKey, Duration.ZERO);
        List<KeyRow> before = rows();

        assertThatThrownBy(() -> deploy("k1", firstKey, GRACE))
                .satisfies(failure -> {
                    assertThat(causeChain(failure)).anyMatch(message -> message.contains("'k1' is retired"));
                    assertThat(causeChain(failure)).noneMatch(message -> containsKeyMaterial(message));
                });

        assertThat(rows()).isEqualTo(before);
        assertThat(verifiedTenant(new HmacDatabaseContextSigner(firstKey, "k1").issueTenantContext(TENANT))).isNull();
        assertThat(verifiedTenant(new HmacDatabaseContextSigner(secondKey, "k2").issueTenantContext(TENANT)))
                .isEqualTo(TENANT.value());
    }

    @Test
    void keyMaterialIsReadOnlyThroughTheRetirementAwareLookup() throws Exception {
        deploy("k1", firstKey, GRACE);
        List<String> readers = new ArrayList<>();
        try (Connection migration = migrationConnection(); PreparedStatement statement = migration.prepareStatement("""
                SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'dokene' AND p.prosrc LIKE '%tenant_context_signing_keys%'
                ORDER BY p.proname
                """); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                readers.add(result.getString(1));
            }
        }
        assertThat(readers).containsExactly("verification_signing_key");

        try (Connection runtime = runtimeConnection(); PreparedStatement call = runtime.prepareStatement(
                "SELECT dokene.verification_signing_key('k1')")) {
            assertThatThrownBy(call::executeQuery).isInstanceOfSatisfying(SQLException.class,
                    exception -> assertThat(exception.getSQLState()).isEqualTo("42501"));
        }
    }

    private void deploy(String keyId, String key, Duration grace) {
        Flyway.configure()
                .dataSource(url, TenantSecurityIntegrationFixture.MIGRATION_ROLE,
                        TenantSecurityIntegrationFixture.MIGRATION_PASSWORD)
                .schemas("dokene").defaultSchema("dokene").createSchemas(true).cleanDisabled(true)
                .callbacks(new TenantContextSigningKeyCallback(key, keyId, grace))
                .load()
                .migrate();
    }

    private UUID verifiedTenant(SignedDatabaseContext context) throws SQLException {
        try (Connection runtime = runtimeConnection()) {
            try (PreparedStatement settings = runtime.prepareStatement(
                    "SELECT set_config('dokene.tenant_context', ?, false), set_config('dokene.tenant_context_signature', ?, false)")) {
                settings.setString(1, context.payload());
                settings.setString(2, context.signature());
                settings.execute();
            }
            try (PreparedStatement query = runtime.prepareStatement("SELECT dokene.current_verified_tenant_id()");
                    ResultSet result = query.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private enum KeyCheck { ACCEPTED, KEY_REJECTED }

    /**
     * Calls the audit append with a provider capability and no tenant context. The key is checked before the tenant,
     * so an accepted key fails later on the tenant mismatch and no row is ever written.
     */
    private KeyCheck auditKeyCheck(SignedDatabaseContext capability) throws SQLException {
        try (Connection runtime = runtimeConnection(); PreparedStatement append = runtime.prepareStatement("""
                SELECT dokene.append_audit_event(
                    p_id => ?, p_occurred_at => statement_timestamp(), p_event_type => 'MESSAGE_DELIVERY_UPDATED',
                    p_target_type => 'MESSAGE', p_target_id => ?, p_outcome => 'SUCCESS', p_correlation_id => ?,
                    p_permission => NULL, p_denial_reason => NULL, p_previous_role => NULL, p_new_role => NULL,
                    p_context_payload => ?, p_context_signature => ?)
                """)) {
            append.setObject(1, UUID.randomUUID());
            append.setObject(2, UUID.randomUUID());
            append.setObject(3, UUID.randomUUID());
            append.setString(4, capability.payload());
            append.setString(5, capability.signature());
            SQLException failure = null;
            try {
                append.executeQuery().close();
            } catch (SQLException exception) {
                failure = exception;
            }
            assertThat((Throwable) failure).as("no tenant context, so the append never succeeds").isNotNull();
            assertThat(failure.getSQLState()).isEqualTo("28000");
            if (failure.getMessage().contains("Active signing key not found")) {
                return KeyCheck.KEY_REJECTED;
            }
            assertThat(failure.getMessage()).contains("does not match active verified tenant");
            return KeyCheck.ACCEPTED;
        }
    }

    private void moveDeadlineIntoThePast(String keyId) throws SQLException {
        try (Connection migration = migrationConnection(); PreparedStatement update = migration.prepareStatement(
                "UPDATE dokene.tenant_context_signing_keys SET retire_after = statement_timestamp() - INTERVAL '1 second' WHERE key_id = ?")) {
            update.setString(1, keyId);
            assertThat(update.executeUpdate()).isOne();
        }
    }

    private record KeyRow(String keyId, String status, Instant retireAfter, Instant retiredAt, String fingerprint) {
    }

    private KeyRow row(String keyId) throws SQLException {
        return rows().stream().filter(row -> row.keyId().equals(keyId)).findFirst().orElseThrow();
    }

    /** Key rows with a digest instead of the key material, so the test never prints a key. */
    private List<KeyRow> rows() throws SQLException {
        List<KeyRow> rows = new ArrayList<>();
        try (Connection migration = migrationConnection(); PreparedStatement query = migration.prepareStatement("""
                SELECT key_id, status, retire_after, retired_at, md5(signing_key)
                FROM dokene.tenant_context_signing_keys ORDER BY key_id
                """); ResultSet result = query.executeQuery()) {
            while (result.next()) {
                rows.add(new KeyRow(result.getString(1), result.getString(2), instant(result, 3), instant(result, 4),
                        result.getString(5)));
            }
        }
        return rows;
    }

    private Instant databaseNow() throws SQLException {
        try (Connection migration = migrationConnection(); PreparedStatement query = migration.prepareStatement(
                "SELECT statement_timestamp()"); ResultSet result = query.executeQuery()) {
            result.next();
            return instant(result, 1);
        }
    }

    private static Instant instant(ResultSet result, int column) throws SQLException {
        java.sql.Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private boolean containsKeyMaterial(String message) {
        String lower = message.toLowerCase();
        return lower.contains(firstKey.toLowerCase()) || lower.contains(secondKey.toLowerCase());
    }

    private static List<String> causeChain(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return messages;
    }

    private Connection migrationConnection() throws SQLException {
        return DriverManager.getConnection(url, TenantSecurityIntegrationFixture.MIGRATION_ROLE,
                TenantSecurityIntegrationFixture.MIGRATION_PASSWORD);
    }

    private Connection runtimeConnection() throws SQLException {
        return DriverManager.getConnection(url, TenantSecurityIntegrationFixture.RUNTIME_ROLE,
                TenantSecurityIntegrationFixture.RUNTIME_PASSWORD);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return HexFormat.of().formatHex(key);
    }
}
