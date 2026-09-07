package io.github.stevdrey.dokene.customer.domain;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ContactPolicyEvent(UUID id, Type type, UUID contactId, ContactChannel channel,
        ConsentStatus consentStatus, Boolean doNotContact, ContactIntentSource source, Instant occurredAt,
        IdentityId actorId, TenantMembershipId membershipId, long policyVersion) {
    public enum Type { CONSENT_CHANGED, DO_NOT_CONTACT_CHANGED }

    public ContactPolicyEvent {
        Objects.requireNonNull(id, "Event ID is required");
        Objects.requireNonNull(type, "Event type is required");
        Objects.requireNonNull(source, "Event source is required");
        Objects.requireNonNull(occurredAt, "Event timestamp is required");
        Objects.requireNonNull(actorId, "Event actor is required");
        Objects.requireNonNull(membershipId, "Event membership is required");
        if (policyVersion < 1) {
            throw new IllegalArgumentException("Event policy version must be positive");
        }
        if (type == Type.CONSENT_CHANGED
                && (contactId == null || channel == null || consentStatus == null
                    || consentStatus == ConsentStatus.UNKNOWN || doNotContact != null)) {
            throw new IllegalArgumentException("Invalid consent event");
        }
        if (type == Type.DO_NOT_CONTACT_CHANGED
                && (contactId != null || channel != null || consentStatus != null || doNotContact == null)) {
            throw new IllegalArgumentException("Invalid do-not-contact event");
        }
    }
}
