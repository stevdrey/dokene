// ABOUTME: A tenant's Meta WhatsApp sending identity and non-secret configuration (ADR 0023 §3.3, issue 134).
// ABOUTME: Holds Meta ids, the human display number and a secret reference only; never secret material.
package io.github.stevdrey.dokene.integration.domain;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TimestampPrecision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record WhatsAppIntegration(UUID id, TenantId tenantId, String phoneNumberId, String businessAccountId,
        String displayPhoneNumber, boolean enabled, String secretRef, long credentialRevision, Instant createdAt,
        Instant updatedAt, long version) {
    /** Meta ids are numeric strings; they are identifiers, not phone numbers. */
    private static final Pattern META_ID = Pattern.compile("^[0-9]{5,32}$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9][0-9]{7,14}$");
    private static final Pattern SECRET_REF = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    public WhatsAppIntegration {
        Objects.requireNonNull(id, "Integration id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        requireMatch(phoneNumberId, META_ID, "Phone number id is invalid");
        requireMatch(businessAccountId, META_ID, "Business account id is invalid");
        if (displayPhoneNumber != null && !E164.matcher(displayPhoneNumber).matches()) {
            throw new IllegalArgumentException("Display phone number is invalid");
        }
        requireMatch(secretRef, SECRET_REF, "Secret reference is invalid");
        // PostgreSQL stores microseconds; normalizing keeps in-memory values equal to what a read returns.
        createdAt = TimestampPrecision.normalize(Objects.requireNonNull(createdAt, "Created at is required"));
        updatedAt = TimestampPrecision.normalize(Objects.requireNonNull(updatedAt, "Updated at is required"));
        if (credentialRevision < 1 || version < 0) {
            throw new IllegalArgumentException("Revision is invalid");
        }
    }

    public static WhatsAppIntegration create(TenantId tenantId, String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, boolean enabled, String secretRef, Instant now) {
        return new WhatsAppIntegration(UUID.randomUUID(), tenantId, phoneNumberId, businessAccountId,
                displayPhoneNumber, enabled, secretRef, 1, now, now, 0);
    }

    /** Rotating or switching the secret reference bumps the credential revision, which voids open approvals. */
    public WhatsAppIntegration update(String phoneNumberId, String businessAccountId, String displayPhoneNumber,
            boolean enabled, String secretRef, Instant now) {
        long revision = Objects.equals(this.secretRef, secretRef) ? credentialRevision : credentialRevision + 1;
        return new WhatsAppIntegration(id, tenantId, phoneNumberId, businessAccountId, displayPhoneNumber, enabled,
                secretRef, revision, createdAt, now, version);
    }

    /** The same record with the version the store reported after a committed write. */
    public WhatsAppIntegration withVersion(long storedVersion) {
        return new WhatsAppIntegration(id, tenantId, phoneNumberId, businessAccountId, displayPhoneNumber, enabled,
                secretRef, credentialRevision, createdAt, updatedAt, storedVersion);
    }

    private static void requireMatch(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(message);
        }
    }
}
