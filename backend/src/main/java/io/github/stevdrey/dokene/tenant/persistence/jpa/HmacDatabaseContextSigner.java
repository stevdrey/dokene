package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
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
    private final String keyId;

    @Autowired
    public HmacDatabaseContextSigner(
            @Value("${dokene.tenant-context.signing-key}") String signingKey,
            @Value("${dokene.tenant-context.key-id:default}") String keyId
    ) {
        this(signingKey, keyId, Clock.systemUTC(), new SecureRandom());
    }

    HmacDatabaseContextSigner(String signingKey, Clock clock, SecureRandom secureRandom) {
        this(signingKey, "default", clock, secureRandom);
    }

    HmacDatabaseContextSigner(String signingKey, String keyId, Clock clock, SecureRandom secureRandom) {
        this.signingKey = parseSigningKey(signingKey);
        this.keyId = parseKeyId(keyId);
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

    @Override
    public SignedDatabaseContext issueAuditContext(
            TenantId tenantId,
            IdentityId actorId,
            TenantMembershipId membershipId
    ) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(actorId, "Actor ID is required");
        Objects.requireNonNull(membershipId, "Membership ID is required");
        Instant expiresAt = clock.instant().plusSeconds(CAPABILITY_LIFETIME_SECONDS);
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        String payload = "%s|%s|%s|%s|%s|%d|%s".formatted(
                "audit",
                keyId,
                tenantId.value(),
                actorId.value(),
                membershipId.value(),
                expiresAt.getEpochSecond(),
                HexFormat.of().formatHex(nonce)
        );
        return new SignedDatabaseContext(payload, sign(payload), expiresAt);
    }

    public String keyId() {
        return keyId;
    }

    private SignedDatabaseContext issue(String scope, UUID subjectId) {
        Instant expiresAt = clock.instant().plusSeconds(CAPABILITY_LIFETIME_SECONDS);
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        String payload = "%s|%s|%s|%d|%s".formatted(
                scope,
                keyId,
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

    private static byte[] parseSigningKey(String rawSigningKey) {
        if (rawSigningKey == null || !rawSigningKey.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "DOKENE_TENANT_CONTEXT_SIGNING_KEY must be a 32-byte hexadecimal value"
            );
        }
        return HexFormat.of().parseHex(rawSigningKey);
    }

    private static String parseKeyId(String rawKeyId) {
        if (rawKeyId == null || !rawKeyId.matches("^[0-9a-zA-Z_-]{1,32}$")) {
            throw new IllegalArgumentException(
                    "DOKENE_TENANT_CONTEXT_KEY_ID must be 1 to 32 characters matching [0-9a-zA-Z_-]"
            );
        }
        return rawKeyId;
    }
}
