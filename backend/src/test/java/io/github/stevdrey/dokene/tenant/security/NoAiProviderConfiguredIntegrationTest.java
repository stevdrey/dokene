package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.provider.disabled.DisabledAiProvider;
import io.github.stevdrey.dokene.ai.provider.fake.DefaultFakeAiProvider;
import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.ContactPolicyService;
import io.github.stevdrey.dokene.customer.application.CustomerService;
import io.github.stevdrey.dokene.customer.application.CustomerService.PhoneInput;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationResult;
import io.github.stevdrey.dokene.followup.application.FollowUpRecommendationService;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.followup.application.RecommendationStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.purchase.application.PurchaseService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.context;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedMembership;
import static io.github.stevdrey.dokene.tenant.security.TenantSecurityIntegrationFixture.seedTenant;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NoAiProviderConfiguredIntegrationTest {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws SQLException {
        TenantSecurityIntegrationFixture.configure(registry);
    }

    @Autowired ApplicationContext applicationContext;
    @Autowired FollowUpService followUps;
    @Autowired FollowUpRecommendationService recommendations;
    @Autowired CustomerService customers;
    @Autowired ContactPolicyService contacts;
    @Autowired PurchaseService purchases;
    @Autowired TenantRepository tenants;
    @Autowired TenantMembershipRepository memberships;
    @Autowired TenantContextProvider contexts;
    @Autowired AuditExecutionContext auditExecution;

    private Tenant tenant;
    private TenantContext tenantContext;
    private Customer customer;

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        tenant = seedTenant(tenants, "No AI Tenant " + UUID.randomUUID(), now);
        tenantContext = context(seedMembership(memberships, contexts, tenant.id(),
                new IdentityId(UUID.randomUUID()), TenantRole.OWNER, now));

        customer = inContext(tenantContext, () -> customers.create(
                "Jane Doe",
                null,
                List.of(new PhoneInput("8888" + String.format("%04d",
                        Math.abs(UUID.randomUUID().hashCode()) % 10000), "CR", true))));
    }

    @Test
    void applicationContextBootsWithDisabledAiProviderWhenNoProviderIsConfigured() {
        assertThat(applicationContext.getBeansOfType(AiProvider.class)).hasSize(1);
        AiProvider provider = applicationContext.getBean(AiProvider.class);
        assertThat(provider).isInstanceOf(DisabledAiProvider.class);
        assertThat(applicationContext.getBeansOfType(DefaultFakeAiProvider.class)).isEmpty();
    }

    @Test
    void manualFollowUpWorkflowsOperateNormallyWithoutAiProvider() throws Exception {
        // Configure tenant policy
        var policy = inContext(tenantContext, () -> followUps.configureTenant(14, ZoneId.of("America/Costa_Rica"), 0L));
        assertThat(policy.cadenceDays()).isEqualTo(14);

        // Customer 1: Record manual follow-up
        Customer c1 = createDueCustomer("Customer 1", "88880001", "order-101");
        var eval1 = inContext(tenantContext, () -> followUps.evaluate(c1.id()));
        assertThat(eval1.status()).isIn(FollowUpStatus.DUE, FollowUpStatus.OVERDUE);
        var policy1 = inContext(tenantContext, () -> followUps.customerPolicy(c1.id()));
        var manualResult = inContext(tenantContext, () ->
                followUps.recordManualFollowUp(c1.id(), policy1.version(), "manual-key-1", "Called customer"));
        assertThat(manualResult.created()).isTrue();

        // Customer 2: Snooze follow-up
        Customer c2 = createDueCustomer("Customer 2", "88880002", "order-102");
        var policy2 = inContext(tenantContext, () -> followUps.customerPolicy(c2.id()));
        var snoozed = inContext(tenantContext, () ->
                followUps.snooze(c2.id(), LocalDate.now(ZoneId.of("America/Costa_Rica")).plusDays(5),
                        policy2.version()));
        assertThat(snoozed.snoozedUntil()).isNotNull();

        // Customer 3: Dismiss follow-up
        Customer c3 = createDueCustomer("Customer 3", "88880003", "order-103");
        var policy3 = inContext(tenantContext, () -> followUps.customerPolicy(c3.id()));
        var dismissed = inContext(tenantContext, () ->
                followUps.dismiss(c3.id(), policy3.version(), "dismiss-key-1", "Customer requested dismissal"));
        assertThat(dismissed.created()).isTrue();

        // Customer 4: Verify due queue listing
        Customer c4 = createDueCustomer("Customer 4", "88880004", "order-104");
        var queue = inContext(tenantContext, () ->
                followUps.dueQueue(new io.github.stevdrey.dokene.followup.application.FollowUpQueueQuery(null, null, 10)));
        assertThat(queue.items()).isNotEmpty();
        assertThat(queue.items().stream().anyMatch(item -> item.customerId().equals(c4.id()))).isTrue();
    }

    private Customer createDueCustomer(String name, String phoneNum, String purchaseKey) throws Exception {
        Customer c = inContext(tenantContext, () -> customers.create(
                name, null, List.of(new PhoneInput(phoneNum, "CR", true))));
        UUID contactId = c.phones().getFirst().id();
        var cp = inContext(tenantContext, () -> contacts.get(c.id()));
        inContext(tenantContext, () -> contacts.changeConsent(c.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, cp.version()));
        Instant past = LocalDate.now(ZoneId.of("America/Costa_Rica")).minusDays(20)
                .atStartOfDay(ZoneId.of("America/Costa_Rica")).toInstant();
        inContext(tenantContext, () -> purchases.record(c.id(), past, "Item", purchaseKey));
        return c;
    }

    @Test
    void recommendationRequestDegradesGracefullyToAiUnavailable() throws Exception {
        inContext(tenantContext, () -> followUps.configureTenant(14, ZoneId.of("America/Costa_Rica"), 0L));

        UUID contactId = customer.phones().getFirst().id();
        var contactPolicy = inContext(tenantContext, () -> contacts.get(customer.id()));
        inContext(tenantContext, () -> contacts.changeConsent(customer.id(), contactId, ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, contactPolicy.version()));

        Instant pastPurchase = LocalDate.now(ZoneId.of("America/Costa_Rica")).minusDays(20)
                .atStartOfDay(ZoneId.of("America/Costa_Rica")).toInstant();
        inContext(tenantContext, () -> purchases.record(customer.id(), pastPurchase, "Coffee Beans", "order-102"));

        FollowUpRecommendationResult result = inContext(tenantContext, () ->
                recommendations.recommendSafe(customer.id(), null, null));

        assertThat(result.status()).isEqualTo(RecommendationStatus.AI_UNAVAILABLE);
        assertThat(result.unavailableReason())
                .isEqualTo(io.github.stevdrey.dokene.followup.application.AiUnavailableReason.NOT_AVAILABLE);
        assertThat(result.evaluation()).isNotNull();
        assertThat(result.evaluation().customerId()).isEqualTo(customer.id());
        assertThat(result.policyVersion()).isNotNull();
        assertThat(result.recommendation()).isNull();
    }

    private <T> T inContext(TenantContext context, Callable<T> operation) throws Exception {
        return auditExecution.callWithCorrelation(UUID.randomUUID(),
                () -> contexts.callWithContext(context, operation::call));
    }
}
