package io.github.stevdrey.dokene.identity.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.identity.application.OidcIdentityMapping;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OidcIdentityPersistenceIntegrationTest {

    private static final long OLDER_LOGIN_LOCK = 739_301L;
    private static final long OLDER_LOGIN_REACHED_LOCK = 739_302L;

    @Autowired
    private JdbcOidcIdentityResolver resolver;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "6");
    }

    @Test
    void repeatedAndConcurrentFirstLoginsResolveOneStableInternalIdentity() throws Exception {
        OidcIdentityMapping mapping = new OidcIdentityMapping(
                URI.create("https://identity.example.test/issuer"),
                "concurrent-subject"
        );
        List<Callable<IdentityId>> logins = java.util.stream.IntStream.range(0, 12)
                .mapToObj(ignored -> (Callable<IdentityId>) () -> resolver.resolve(mapping))
                .toList();

        List<IdentityId> identities;
        try (var executor = Executors.newFixedThreadPool(6)) {
            identities = executor.invokeAll(logins).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
        }

        assertThat(identities).containsOnly(identities.getFirst());
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                var statement = connection.prepareStatement("""
                        SELECT count(*)
                        FROM dokene.oidc_identity_mappings
                        WHERE issuer = ? AND subject = ?
                        """)) {
            statement.setString(1, mapping.issuer().toString());
            statement.setString(2, mapping.subject());
            try (var result = statement.executeQuery()) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }

        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext();
                var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.executeQuery("SELECT * FROM dokene.oidc_identity_mappings"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void olderInvocationCannotMoveLastAuthenticationBehindANewerConcurrentLogin() throws Exception {
        OidcIdentityMapping mapping = new OidcIdentityMapping(
                URI.create("https://identity.example.test/issuer"),
                "ordered-" + UUID.randomUUID()
        );
        installOlderLoginBarrier();

        try (var executor = Executors.newSingleThreadExecutor();
                Connection barrier = TenantSecurityIntegrationFixture.migrationConnection()) {
            barrier.setAutoCommit(false);
            try (PreparedStatement lock = barrier.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, OLDER_LOGIN_LOCK);
                lock.execute();
            }

            Future<IdentityId> olderLogin = executor.submit(
                    () -> resolveWithApplicationName(mapping, "dokene-older-oidc-login")
            );
            awaitOlderLoginAtBarrier();

            IdentityId newerIdentity = resolveWithApplicationName(mapping, "dokene-newer-oidc-login");
            MappingTimestamps afterNewerLogin = readTimestamps(mapping);
            barrier.commit();
            IdentityId olderIdentity = olderLogin.get();
            MappingTimestamps afterOlderLogin = readTimestamps(mapping);

            assertThat(olderIdentity).isEqualTo(newerIdentity);
            assertThat(countMappings(mapping)).isEqualTo(1);
            assertThat(afterOlderLogin.lastAuthenticatedAt()).isEqualTo(afterNewerLogin.lastAuthenticatedAt());
            assertThat(afterOlderLogin.lastAuthenticatedAt()).isAfterOrEqualTo(afterOlderLogin.createdAt());
        } finally {
            removeOlderLoginBarrier();
        }
    }

    private void installOlderLoginBarrier() throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION dokene.pause_older_oidc_login_for_test()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        IF current_setting('application_name') = 'dokene-older-oidc-login' THEN
                            PERFORM pg_advisory_lock(%d);
                            PERFORM pg_advisory_xact_lock(%d);
                        END IF;
                        RETURN NEW;
                    END;
                    $$
                    """.formatted(OLDER_LOGIN_REACHED_LOCK, OLDER_LOGIN_LOCK));
            statement.execute("""
                    CREATE TRIGGER pause_older_oidc_login_for_test
                    BEFORE INSERT ON dokene.oidc_identity_mappings
                    FOR EACH ROW EXECUTE FUNCTION dokene.pause_older_oidc_login_for_test()
                    """);
        }
    }

    private void removeOlderLoginBarrier() throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                var statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS pause_older_oidc_login_for_test ON dokene.oidc_identity_mappings");
            statement.execute("DROP FUNCTION IF EXISTS dokene.pause_older_oidc_login_for_test()");
        }
    }

    private void awaitOlderLoginAtBarrier() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                    PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                statement.setLong(1, OLDER_LOGIN_REACHED_LOCK);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        return;
                    }
                }
                try (PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                    unlock.setLong(1, OLDER_LOGIN_REACHED_LOCK);
                    unlock.execute();
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Older OIDC login did not reach the deterministic database barrier");
    }

    private IdentityId resolveWithApplicationName(OidcIdentityMapping mapping, String applicationName)
            throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.runtimeConnectionWithoutContext();
                var setName = connection.prepareStatement("SELECT set_config('application_name', ?, false)");
                var resolve = connection.prepareStatement("SELECT dokene.resolve_oidc_identity(?, ?)")) {
            setName.setString(1, applicationName);
            setName.execute();
            resolve.setString(1, mapping.issuer().toString());
            resolve.setString(2, mapping.subject());
            try (var result = resolve.executeQuery()) {
                result.next();
                return new IdentityId(result.getObject(1, UUID.class));
            }
        }
    }

    private int countMappings(OidcIdentityMapping mapping) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT count(*) FROM dokene.oidc_identity_mappings WHERE issuer = ? AND subject = ?
                        """)) {
            statement.setString(1, mapping.issuer().toString());
            statement.setString(2, mapping.subject());
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private MappingTimestamps readTimestamps(OidcIdentityMapping mapping) throws SQLException {
        try (Connection connection = TenantSecurityIntegrationFixture.migrationConnection();
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT created_at, last_authenticated_at
                        FROM dokene.oidc_identity_mappings
                        WHERE issuer = ? AND subject = ?
                        """)) {
            statement.setString(1, mapping.issuer().toString());
            statement.setString(2, mapping.subject());
            try (var result = statement.executeQuery()) {
                result.next();
                return new MappingTimestamps(
                        result.getObject("created_at", OffsetDateTime.class).toInstant(),
                        result.getObject("last_authenticated_at", OffsetDateTime.class).toInstant()
                );
            }
        }
    }

    private record MappingTimestamps(Instant createdAt, Instant lastAuthenticatedAt) {
    }
}
