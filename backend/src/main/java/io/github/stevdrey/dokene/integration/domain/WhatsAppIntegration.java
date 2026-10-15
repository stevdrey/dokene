// ABOUTME: A tenant's Meta WhatsApp sending identity and non-secret configuration (ADR 0023 §3.3, issue 134).
// ABOUTME: Holds Meta ids, the human display number, a secret reference and when ownership was proven; never secrets.
package io.github.stevdrey.dokene.integration.domain;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TimestampPrecision;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record WhatsAppIntegration(UUID id, TenantId tenantId, String phoneNumberId, String businessAccountId,
        String displayPhoneNumber, boolean enabled, String secretRef, long credentialRevision, Instant createdAt,
        Instant updatedAt, long version, Instant ownershipVerifiedAt) {
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
        ownershipVerifiedAt = ownershipVerifiedAt == null ? null : TimestampPrecision.normalize(ownershipVerifiedAt);
    }

    public static WhatsAppIntegration create(TenantId tenantId, String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, boolean enabled, String secretRef, Instant now) {
        return new WhatsAppIntegration(UUID.randomUUID(), tenantId, phoneNumberId, businessAccountId,
                displayPhoneNumber, enabled, secretRef, 1, now, now, 0, null);
    }

    /**
     * Rotating or switching the secret reference bumps the credential revision, which voids open approvals. A proof of
     * ownership holds for one enabled phone number, business account and credential revision, so changing any of them or
     * disabling the integration clears it (issue 210): a disabled tenant never holds a number another tenant could prove.
     * Changing the display number keeps it.
     */
    public WhatsAppIntegration update(String phoneNumberId, String businessAccountId, String displayPhoneNumber,
            boolean enabled, String secretRef, Instant now) {
        long revision = Objects.equals(this.secretRef, secretRef) ? credentialRevision : credentialRevision + 1;
        boolean sameIdentity = enabled && this.phoneNumberId.equals(phoneNumberId)
                && this.businessAccountId.equals(businessAccountId) && revision == credentialRevision;
        return new WhatsAppIntegration(id, tenantId, phoneNumberId, businessAccountId, displayPhoneNumber, enabled,
                secretRef, revision, createdAt, now, version, sameIdentity ? ownershipVerifiedAt : null);
    }

    /**
     * Records that the credential behind the unchanged secret reference was replaced in the secret store. Bumps the
     * credential revision, which is part of the approval sender identity, so open approvals fail at send time. The new
     * credential has not been shown to reach the number, so ownership must be proven again.
     */
    public WhatsAppIntegration rotateCredential(Instant now) {
        return new WhatsAppIntegration(id, tenantId, phoneNumberId, businessAccountId, displayPhoneNumber, enabled,
                secretRef, credentialRevision + 1, createdAt, now, version, null);
    }

    /**
     * Records that the provider adapter proved, with this tenant's current credential, that the phone number belongs
     * to the configured business account. Only the ownership verification flow may call this, and only while enabled.
     */
    public WhatsAppIntegration verifyOwnership(Instant now) {
        if (!enabled) {
            throw new IllegalStateException("A disabled integration cannot hold an ownership proof");
        }
        return new WhatsAppIntegration(id, tenantId, phoneNumberId, businessAccountId, displayPhoneNumber, enabled,
                secretRef, credentialRevision, createdAt, now, version, now);
    }

    /** True only while a proof made with the current credential revision is stored. */
    public boolean ownershipVerified() {
        return ownershipVerifiedAt != null;
    }

    /** The same record with the version the store reported after a committed write. */
    public WhatsAppIntegration withVersion(long storedVersion) {
        return new WhatsAppIntegration(id, tenantId, phoneNumberId, businessAccountId, displayPhoneNumber, enabled,
                secretRef, credentialRevision, createdAt, updatedAt, storedVersion, ownershipVerifiedAt);
    }

    /** True for a well-formed Meta identifier (a numeric string, not a phone number). */
    public static boolean isMetaId(String value) {
        return value != null && META_ID.matcher(value).matches();
    }

    private static void requireMatch(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(message);
        }
    }
}
