package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactEligibility;
import io.github.stevdrey.dokene.customer.domain.ContactEligibilityReason;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContactPolicyService {
    private final CustomerRepository customers;
    private final ContactPolicyRepository policies;
    private final ContactPolicyAuditPort audit;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final Clock clock;

    public ContactPolicyService(CustomerRepository customers, ContactPolicyRepository policies,
            ContactPolicyAuditPort audit, TenantAuthorizationService authorization,
            TenantContextProvider contexts, Clock clock) {
        this.customers = Objects.requireNonNull(customers, "Customer repository is required");
        this.policies = Objects.requireNonNull(policies, "Contact policy repository is required");
        this.audit = Objects.requireNonNull(audit, "Contact policy audit is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public ContactPolicy get(CustomerId customerId) {
        return policies.find(requireCustomer(customerId, TenantPermission.CUSTOMER_READ));
    }

    @Transactional
    public ContactPolicy changeConsent(CustomerId customerId, UUID contactId, ContactChannel channel,
            ConsentStatus status, ContactIntentSource source, long expectedVersion) {
        if (status == null || status == ConsentStatus.UNKNOWN) {
            throw new IllegalArgumentException("Consent must be granted or revoked");
        }
        Objects.requireNonNull(contactId, "Contact ID is required");
        Objects.requireNonNull(channel, "Contact channel is required");
        Objects.requireNonNull(source, "Consent source is required");
        Customer customer = requireCustomer(customerId, TenantPermission.CUSTOMER_WRITE);
        if (customer.status() == CustomerStatus.ARCHIVED) {
            throw new IllegalStateException("Archived customer contact policy cannot be changed");
        }
        if (customer.phones().stream().noneMatch(phone -> phone.id().equals(contactId))) {
            throw new CustomerNotFoundException();
        }
        TenantContext actor = contexts.requireCurrent();
        policies.changeConsent(customer, contactId, channel, status, source, expectedVersion, actor, clock.instant());
        audit.consentChanged(customer.id());
        return policies.find(customer);
    }

    @Transactional
    public ContactPolicy changeDoNotContact(CustomerId customerId, boolean enabled,
            ContactIntentSource source, long expectedVersion) {
        Objects.requireNonNull(source, "Do-not-contact source is required");
        Customer customer = requireCustomer(customerId, TenantPermission.CUSTOMER_WRITE);
        if (customer.status() == CustomerStatus.ARCHIVED) {
            throw new IllegalStateException("Archived customer contact policy cannot be changed");
        }
        TenantContext actor = contexts.requireCurrent();
        policies.changeDoNotContact(customer, enabled, source, expectedVersion, actor, clock.instant());
        audit.doNotContactChanged(customer.id());
        return policies.find(customer);
    }

    @Transactional(readOnly = true)
    public ContactEligibility evaluate(CustomerId customerId, UUID contactId, ContactChannel channel) {
        Objects.requireNonNull(contactId, "Contact ID is required");
        Objects.requireNonNull(channel, "Contact channel is required");
        Customer customer = requireCustomer(customerId, TenantPermission.CUSTOMER_READ);
        ContactPolicy policy = policies.find(customer);
        var reasons = new ArrayList<ContactEligibilityReason>();
        if (policy.doNotContact()) reasons.add(ContactEligibilityReason.DO_NOT_CONTACT);
        if (customer.status() == CustomerStatus.ARCHIVED) reasons.add(ContactEligibilityReason.CUSTOMER_ARCHIVED);
        boolean activeContact = customer.phones().stream().anyMatch(phone -> phone.id().equals(contactId));
        if (!activeContact) {
            reasons.add(ContactEligibilityReason.CONTACT_NOT_ACTIVE);
        } else {
            ConsentStatus status = policy.consents().stream()
                    .filter(consent -> consent.contactId().equals(contactId) && consent.channel() == channel)
                    .findFirst().orElseThrow().status();
            if (status == ConsentStatus.UNKNOWN) reasons.add(ContactEligibilityReason.CONSENT_UNKNOWN);
            if (status == ConsentStatus.REVOKED) reasons.add(ContactEligibilityReason.CONSENT_REVOKED);
        }
        return new ContactEligibility(reasons.isEmpty(), reasons);
    }

    @Transactional(readOnly = true)
    public ContactPolicyPage history(CustomerId customerId, ContactPolicyCursor before, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("History limit must be between 1 and 100");
        Customer customer = requireCustomer(customerId, TenantPermission.CUSTOMER_READ);
        var events = policies.history(customer, before, limit + 1);
        boolean hasNext = events.size() > limit;
        var page = hasNext ? events.subList(0, limit) : events;
        String next = hasNext ? new ContactPolicyCursor(page.getLast().occurredAt(), page.getLast().id()).encode() : null;
        return new ContactPolicyPage(page, next);
    }

    private Customer requireCustomer(CustomerId customerId, TenantPermission permission) {
        authorization.requirePermission(permission);
        Customer customer = customers.findById(contexts.requireCurrent().tenantId(),
                Objects.requireNonNull(customerId, "Customer ID is required"))
                .orElseThrow(CustomerNotFoundException::new);
        authorization.requireResourceAccess(permission, customer);
        return customer;
    }
}
