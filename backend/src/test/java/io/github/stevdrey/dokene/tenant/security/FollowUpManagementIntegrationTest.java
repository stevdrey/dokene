package io.github.stevdrey.dokene.tenant.security;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class FollowUpManagementIntegrationTest {
    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @Autowired FollowUpService followUps;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired CustomerService customers;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;

    private TenantContext contextA;
    private TenantContext contextB;
    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        var tenantA = seedTenant(tenants, "Follow-up A " + UUID.randomUUID(), now);
        var tenantB = seedTenant(tenants, "Follow-up B " + UUID.randomUUID(), now);
        contextA = context(seedMembership(memberships, contexts, tenantA.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        contextB = context(seedMembership(memberships, contexts, tenantB.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));
        customer = inContext(contextA, () -> customers.create("Follow-up customer", null,
                List.of(new PhoneInput("8888" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
    }

    @Test
    void reflectsCurrentPurchaseAndConsentAndIsolatesPolicyAccess() throws Exception {
        var noConsent = inContext(contextA, () -> followUps.evaluate(customer.id()));
        assertThat(noConsent.reasons()).contains(FollowUpReason.NO_ELIGIBLE_CONTACT);

        var policy = inContext(contextA, () -> contacts.get(customer.id()));
        UUID contactId = customer.phones().getFirst().id();
        inContext(contextA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, policy.version()));
        inContext(contextA, () -> purchases.record(customer.id(), Instant.now().minusSeconds(1),
                "Follow-up anchor", "follow-up-anchor"));
        inContext(contextA, () -> followUps.configureTenant(30, ZoneId.of("UTC")));
        inContext(contextA, () -> followUps.configureCustomer(customer.id(), null, LocalDate.now(ZoneId.of("UTC"))));

        var due = inContext(contextA, () -> followUps.evaluate(customer.id()));
        assertThat(due.status()).isEqualTo(FollowUpStatus.DUE);

        var current = inContext(contextA, () -> contacts.get(customer.id()));
        inContext(contextA, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.REVOKED, ContactIntentSource.CUSTOMER_VERBAL, current.version()));
        assertThat(inContext(contextA, () -> followUps.evaluate(customer.id())).reasons())
                .contains(FollowUpReason.NO_ELIGIBLE_CONTACT);
        assertThatThrownBy(() -> inContext(contextB, () -> followUps.evaluate(customer.id())))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
