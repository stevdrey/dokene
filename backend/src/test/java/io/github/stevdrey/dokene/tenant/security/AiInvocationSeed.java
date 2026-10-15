package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * Seeds a fresh tenant with an owner and one customer that is due for an AI recommendation (WhatsApp consent, a
 * purchase 20 days ago, a 14-day cadence), for integration tests that drive the real recommendation service.
 */
final class AiInvocationSeed {
    private static final ZoneId ZONE = ZoneId.of("America/Costa_Rica");

    record Seeded(TenantContext tenantContext, Customer customer) {
    }

    private final TenantRepository tenants;
    private final TenantMembershipRepository memberships;
    private final TenantContextProvider contexts;
    private final AuditExecutionContext auditExecution;
    private final FollowUpService followUps;
    private final CustomerService customers;
    private final ContactPolicyService contacts;
    private final PurchaseService purchases;

    AiInvocationSeed(TenantRepository tenants, TenantMembershipRepository memberships, TenantContextProvider contexts,
            AuditExecutionContext auditExecution, FollowUpService followUps, CustomerService customers,
            ContactPolicyService contacts, PurchaseService purchases) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.contexts = contexts;
        this.auditExecution = auditExecution;
        this.followUps = followUps;
        this.customers = customers;
        this.contacts = contacts;
        this.purchases = purchases;
    }

    Seeded dueCustomer(String tenantNamePrefix) throws Exception {
        Instant now = Instant.now();
        Tenant tenant = seedTenant(tenants, contexts, tenantNamePrefix + " " + UUID.randomUUID(), now);
        TenantContext tenantContext = context(seedMembership(memberships, contexts, tenant.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        inContext(tenantContext, () -> followUps.configureTenant(14, ZONE, 0L));
        Customer customer = inContext(tenantContext, () -> customers.create("Jane Doe", null,
                List.of(new PhoneInput("8888" + String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000),
                        "CR", true))));
        UUID contactId = customer.phones().getFirst().id();
        var contactPolicy = inContext(tenantContext, () -> contacts.get(customer.id()));
        inContext(tenantContext, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contactPolicy.version()));
        Instant past = LocalDate.now(ZONE).minusDays(20).atStartOfDay(ZONE).toInstant();
        inContext(tenantContext, () -> purchases.record(customer.id(), past, "Coffee Beans",
                "order-" + UUID.randomUUID()));
        return new Seeded(tenantContext, customer);
    }

    /** Runs {@code operation} as a request would: with a correlation id and the tenant context bound. */
    <T> T inContext(TenantContext tenantContext, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(tenantContext, operation::call));
    }
}
