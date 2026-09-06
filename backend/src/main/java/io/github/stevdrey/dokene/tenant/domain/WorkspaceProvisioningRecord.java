package io.github.stevdrey.dokene.tenant.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of a completed workspace provisioning request for idempotency tracking.
 */
public record WorkspaceProvisioningRecord(
        UUID id,
        String idempotencyKey,
        IdentityId identityId,
        TenantId tenantId,
        String displayName,
        Instant createdAt
) {

    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    public WorkspaceProvisioningRecord {
        Objects.requireNonNull(id, "Record ID is required");
        idempotencyKey = normalizeIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(identityId, "Identity ID is required");
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        displayName = Tenant.normalizeDisplayName(displayName);
        Objects.requireNonNull(createdAt, "Creation timestamp is required");
    }

    public static String normalizeIdempotencyKey(String rawKey) {
        if (rawKey == null) {
            throw new IllegalArgumentException("Idempotency key is required");
        }
        if (rawKey.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Idempotency key cannot contain NUL characters");
        }
        validateUnicodeScalars(rawKey);

        int start = 0;
        int end = rawKey.length();
        while (start < end) {
            int codePoint = rawKey.codePointAt(start);
            if (!isWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = rawKey.codePointBefore(end);
            if (!isWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        String normalized = rawKey.substring(start, end);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }
        if (normalized.codePointCount(0, normalized.length()) > IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Idempotency key cannot exceed %d characters".formatted(IDEMPOTENCY_KEY_MAX_LENGTH)
            );
        }
        return normalized;
    }

    private static boolean isWhitespace(int codePoint) {
        return codePoint == 0x0085 || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static void validateUnicodeScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char codeUnit = value.charAt(index);
            if (Character.isHighSurrogate(codeUnit)) {
                if (index + 1 == value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("Idempotency key cannot contain unpaired surrogates");
                }
                index++;
            } else if (Character.isLowSurrogate(codeUnit)) {
                throw new IllegalArgumentException("Idempotency key cannot contain unpaired surrogates");
            }
        }
    }
}
