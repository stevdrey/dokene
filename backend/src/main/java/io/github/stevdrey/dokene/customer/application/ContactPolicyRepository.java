package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.ContactPolicyEvent;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;

public interface ContactPolicyRepository {
    ContactPolicy find(Customer customer);
    long changeConsent(Customer customer, UUID contactId, ContactChannel channel, ConsentStatus status,
            ContactIntentSource source, long expectedVersion, TenantContext actor, Instant occurredAt);
    long changeDoNotContact(Customer customer, boolean enabled, ContactIntentSource source,
            long expectedVersion, TenantContext actor, Instant occurredAt);
    List<ContactPolicyEvent> history(Customer customer, ContactPolicyCursor before, int fetchLimit);

    /**
     * The contact policy version of the history row that started the contact's current WhatsApp {@code GRANTED} run:
     * the first {@code GRANTED} row after the latest non-{@code GRANTED} row (ADR 0024 §2). Empty when the contact's
     * latest consent is not {@code GRANTED}. A revocation followed by a re-grant always yields a higher value, while
     * re-saving {@code GRANTED} over {@code GRANTED} keeps it.
     */
    OptionalLong whatsAppConsentRevision(Customer customer, UUID contactId);
}
