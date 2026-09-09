package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpPolicyEvaluator;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.purchase.application.PurchaseRepository;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FollowUpService {
    private final CustomerRepository customers;
    private final ContactPolicyRepository contacts;
    private final PurchaseRepository purchases;
    private final FollowUpPolicyRepository policies;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final FollowUpPolicyEvaluator evaluator;
    private final Clock clock;

    public FollowUpService(CustomerRepository customers, ContactPolicyRepository contacts,
            PurchaseRepository purchases, FollowUpPolicyRepository policies,
            TenantAuthorizationService authorization, TenantContextProvider contexts, Clock clock) {
        this.customers = Objects.requireNonNull(customers);
        this.contacts = Objects.requireNonNull(contacts);
        this.purchases = Objects.requireNonNull(purchases);
        this.policies = Objects.requireNonNull(policies);
        this.authorization = Objects.requireNonNull(authorization);
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
        evaluator = new FollowUpPolicyEvaluator(clock);
    }

    @Transactional(readOnly = true)
    public FollowUpEvaluation evaluate(CustomerId customerId) {
        Customer customer = requireCustomer(customerId, TenantPermission.FOLLOWUP_EVALUATE);
        var tenantPolicy = policies.tenantPolicy(customer.tenantId());
        var customerPolicy = policies.customerPolicy(customer.tenantId(), customer.id());
        var lastPurchase = purchases.lastValid(customer.tenantId(), customer.id())
                .map(purchase -> purchase.purchasedAt()).orElse(null);
        return evaluator.evaluate(customer, contacts.find(customer), tenantPolicy, customerPolicy, lastPurchase);
    }

    @Transactional(readOnly = true)
    public TenantFollowUpPolicy tenantPolicy() {
        authorization.requirePermission(TenantPermission.FOLLOWUP_READ);
        return policies.tenantPolicy(contexts.requireCurrent().tenantId());
    }

    @Transactional(readOnly = true)
    public CustomerFollowUpPolicy customerPolicy(CustomerId customerId) {
        Customer customer = requireCustomer(customerId, TenantPermission.FOLLOWUP_READ);
        return policies.customerPolicy(customer.tenantId(), customer.id());
    }

    @Transactional
    public TenantFollowUpPolicy configureTenant(int cadenceDays, ZoneId zoneId) {
        authorization.requirePermission(TenantPermission.FOLLOWUP_WRITE);
        return policies.saveTenantPolicy(
                new TenantFollowUpPolicy(contexts.requireCurrent().tenantId(), cadenceDays, zoneId));
    }

    @Transactional
    public CustomerFollowUpPolicy configureCustomer(CustomerId customerId, Integer cadenceDays,
            LocalDate explicitNextDate) {
        Customer customer = requireCustomer(customerId, TenantPermission.FOLLOWUP_WRITE);
        var current = policies.customerPolicy(customer.tenantId(), customer.id());
        return policies.saveCustomerPolicy(new CustomerFollowUpPolicy(customer.tenantId(), customer.id(),
                cadenceDays, explicitNextDate, current.snoozedUntil(), current.lastManualFollowUpDate()));
    }

    @Transactional
    public CustomerFollowUpPolicy recordManualFollowUp(CustomerId customerId) {
        Customer customer = requireCustomer(customerId, TenantPermission.FOLLOWUP_WRITE);
        LocalDate today = LocalDate.now(clock.withZone(policies.tenantPolicy(customer.tenantId()).zoneId()));
        return policies.recordManualFollowUp(customer.tenantId(), customer.id(), today);
    }

    @Transactional
    public CustomerFollowUpPolicy snooze(CustomerId customerId, LocalDate until) {
        Customer customer = requireCustomer(customerId, TenantPermission.FOLLOWUP_WRITE);
        LocalDate today = LocalDate.now(clock.withZone(policies.tenantPolicy(customer.tenantId()).zoneId()));
        if (Objects.requireNonNull(until, "Snooze date is required").isBefore(today)) {
            throw new IllegalArgumentException("Snooze date cannot be in the past");
        }
        return policies.snooze(customer.tenantId(), customer.id(), until);
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
