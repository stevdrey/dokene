package io.github.stevdrey.dokene.identity.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.identity.application.OidcIdentityMapping;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OidcIdentityPersistenceIntegrationTest {

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
}
