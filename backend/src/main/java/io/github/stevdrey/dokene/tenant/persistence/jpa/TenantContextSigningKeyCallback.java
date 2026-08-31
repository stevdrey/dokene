package io.github.stevdrey.dokene.tenant.persistence.jpa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Flyway callback that provisions the active tenant-context signing key into the migration-owned
 * key store ({@code dokene.tenant_context_signing_keys}) using parameterized JDBC binding.
 */
@Component
public class TenantContextSigningKeyCallback implements Callback {

    private final String keyId;
    private final byte[] signingKey;

    @Autowired
    public TenantContextSigningKeyCallback(
            @Value("${dokene.tenant-context.signing-key}") String signingKey,
            @Value("${dokene.tenant-context.key-id:default}") String keyId
    ) {
        this.signingKey = parseSigningKey(signingKey);
        this.keyId = parseKeyId(keyId);
    }

    TenantContextSigningKeyCallback(String keyId, byte[] signingKey) {
        this.keyId = parseKeyId(keyId);
        this.signingKey = Objects.requireNonNull(signingKey, "Signing key is required");
    }

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        if (event != Event.AFTER_MIGRATE) {
            return;
        }

        Connection connection = context.getConnection();
        String sql = """
                INSERT INTO dokene.tenant_context_signing_keys (key_id, signing_key, status)
                VALUES (?, ?, 'ACTIVE')
                ON CONFLICT (key_id) DO UPDATE
                SET signing_key = EXCLUDED.signing_key,
                    status = 'ACTIVE';
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, keyId);
            statement.setBytes(2, signingKey);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "Failed to provision tenant context signing key into dokene.tenant_context_signing_keys",
                    exception
            );
        }
    }

    @Override
    public String getCallbackName() {
        return "TenantContextSigningKeyCallback";
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
