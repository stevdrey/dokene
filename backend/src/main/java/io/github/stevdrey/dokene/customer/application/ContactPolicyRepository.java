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
import java.util.UUID;

public interface ContactPolicyRepository {
    ContactPolicy find(Customer customer);
    long changeConsent(Customer customer, UUID contactId, ContactChannel channel, ConsentStatus status,
            ContactIntentSource source, long expectedVersion, TenantContext actor, Instant occurredAt);
    long changeDoNotContact(Customer customer, boolean enabled, ContactIntentSource source,
            long expectedVersion, TenantContext actor, Instant occurredAt);
    List<ContactPolicyEvent> history(Customer customer, ContactPolicyCursor before, int fetchLimit);
}
