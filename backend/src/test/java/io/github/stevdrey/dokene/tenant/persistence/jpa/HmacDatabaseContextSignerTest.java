package io.github.stevdrey.dokene.tenant.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HmacDatabaseContextSignerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);
    private final SecureRandom secureRandom = new SecureRandom();
    private final String signingKey = randomSigningKey();

    @Test
    void issuesTenantContextWithConfiguredKeyIdAndFivePartPayload() {
        HmacDatabaseContextSigner signer = new HmacDatabaseContextSigner(signingKey, "k1", clock, secureRandom);
        TenantId tenantId = TenantId.random();

        SignedDatabaseContext context = signer.issueTenantContext(tenantId);

        assertThat(context.payload()).startsWith("tenant|k1|" + tenantId.value() + "|1788134460|");
        assertThat(context.payload().split("\\|")).hasSize(5);
        assertThat(context.signature()).matches("^[0-9a-f]{64}$");
        assertThat(context.expiresAt()).isEqualTo(Instant.parse("2026-08-31T00:01:00Z"));
        assertThat(signer.keyId()).isEqualTo("k1");
    }

    @Test
    void issuesIdentityContextWithConfiguredKeyIdAndFivePartPayload() {
        HmacDatabaseContextSigner signer = new HmacDatabaseContextSigner(signingKey, "default", clock, secureRandom);
        IdentityId identityId = new IdentityId(UUID.randomUUID());

        SignedDatabaseContext context = signer.issueIdentityContext(identityId);

        assertThat(context.payload()).startsWith("identity|default|" + identityId.value() + "|1788134460|");
        assertThat(context.payload().split("\\|")).hasSize(5);
        assertThat(context.signature()).matches("^[0-9a-f]{64}$");
        assertThat(context.expiresAt()).isEqualTo(Instant.parse("2026-08-31T00:01:00Z"));
        assertThat(signer.keyId()).isEqualTo("default");
    }

    @Test
    void rejectsInvalidKeyIdOrSigningKey() {
        assertThatThrownBy(() -> new HmacDatabaseContextSigner("invalid", "k1", clock, secureRandom))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a 32-byte hexadecimal value");

        assertThatThrownBy(() -> new HmacDatabaseContextSigner(signingKey, "invalid key with spaces!", clock, secureRandom))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matching [0-9a-zA-Z_-]");
    }

    private static String randomSigningKey() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        return HexFormat.of().formatHex(keyBytes);
    }
}
