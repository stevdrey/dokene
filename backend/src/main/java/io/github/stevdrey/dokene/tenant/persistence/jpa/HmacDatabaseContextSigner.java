package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HMAC issuer for bounded PostgreSQL tenant and identity context capabilities.
 */
@Component
public class HmacDatabaseContextSigner implements DatabaseContextSigner {

    private static final long CAPABILITY_LIFETIME_SECONDS = 60;
    private static final int NONCE_BYTES = 16;

    private final Clock clock;
    private final SecureRandom secureRandom;
    private final byte[] signingKey;

    @Autowired
    public HmacDatabaseContextSigner(@Value("${dokene.tenant-context.signing-key}") String signingKey) {
        this(signingKey, Clock.systemUTC(), new SecureRandom());
    }

    HmacDatabaseContextSigner(String signingKey, Clock clock, SecureRandom secureRandom) {
        this.signingKey = parseSigningKey(signingKey);
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.secureRandom = Objects.requireNonNull(secureRandom, "Secure random is required");
    }

    @Override
    public SignedDatabaseContext issueTenantContext(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        return issue("tenant", tenantId.value());
    }

    @Override
    public SignedDatabaseContext issueIdentityContext(IdentityId identityId) {
        Objects.requireNonNull(identityId, "Identity ID is required");
        return issue("identity", identityId.value());
    }

    private SignedDatabaseContext issue(String scope, UUID subjectId) {
        Instant expiresAt = clock.instant().plusSeconds(CAPABILITY_LIFETIME_SECONDS);
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        String payload = "%s|%s|%d|%s".formatted(
                scope,
                subjectId,
                expiresAt.getEpochSecond(),
                HexFormat.of().formatHex(nonce)
        );
        return new SignedDatabaseContext(payload, sign(payload), expiresAt);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private byte[] parseSigningKey(String rawSigningKey) {
        if (rawSigningKey == null || !rawSigningKey.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "DOKENE_TENANT_CONTEXT_SIGNING_KEY must be a 32-byte hexadecimal value"
            );
        }
        return HexFormat.of().parseHex(rawSigningKey);
    }
}
