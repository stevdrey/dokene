package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditTarget;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpPolicyEvaluator;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.purchase.application.PurchaseRepository;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Instant;
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
    private final AuditRecorder audit;

    public FollowUpService(CustomerRepository customers, ContactPolicyRepository contacts,
            PurchaseRepository purchases, FollowUpPolicyRepository policies,
            TenantAuthorizationService authorization, TenantContextProvider contexts, Clock clock,
            AuditRecorder audit) {
        this.customers = Objects.requireNonNull(customers);
        this.contacts = Objects.requireNonNull(contacts);
        this.purchases = Objects.requireNonNull(purchases);
        this.policies = Objects.requireNonNull(policies);
        this.authorization = Objects.requireNonNull(authorization);
        this.contexts = Objects.requireNonNull(contexts);
        this.clock = Objects.requireNonNull(clock);
        this.audit = Objects.requireNonNull(audit);
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
    public TenantFollowUpPolicy configureTenant(int cadenceDays, ZoneId zoneId, long expectedVersion) {
        authorization.requirePermission(TenantPermission.FOLLOWUP_WRITE);
        var tenantId = contexts.requireCurrent().tenantId();
        var updated = policies.updateTenantPolicy(
                new TenantFollowUpPolicy(tenantId, cadenceDays, zoneId, expectedVersion), expectedVersion);
        audit.followUpMutated(AuditTarget.Type.TENANT, tenantId.value(),
                AuditEventType.TENANT_FOLLOW_UP_POLICY_CHANGED);
        return updated;
    }

    @Transactional
    public CustomerFollowUpPolicy configureCustomer(CustomerId customerId, Integer cadenceDays,
            LocalDate explicitNextDate, long expectedVersion) {
        Customer customer = requireCustomer(customerId, TenantPermission.FOLLOWUP_WRITE);
        new CustomerFollowUpPolicy(customer.tenantId(), customer.id(), cadenceDays, explicitNextDate, null, null,
                expectedVersion);
        var updated = policies.updateCustomerPolicy(customer.tenantId(), customer.id(), cadenceDays,
                explicitNextDate, expectedVersion);
        audit.followUpMutated(AuditTarget.Type.CUSTOMER, customer.id().value(),
                AuditEventType.CUSTOMER_FOLLOW_UP_POLICY_CHANGED);
        return updated;
    }

    @Transactional(readOnly = true)
    public FollowUpQueuePage dueQueue(FollowUpQueueQuery query) {
        authorization.requirePermission(TenantPermission.FOLLOWUP_READ);
        var tenantId = contexts.requireCurrent().tenantId();
        Instant now = clock.instant();
        return policies.findDueQueue(tenantId, query, now);
    }

    @Transactional
    public ManualFollowUpResult recordManualFollowUp(CustomerId customerId, long expectedVersion,
            String idempotencyKey, String notes) {
        Customer customer = requireCustomerForUpdate(customerId, TenantPermission.FOLLOWUP_WRITE);
        var existing = policies.findCompletion(customer.tenantId(), idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().customerId().equals(customerId)) {
                throw new FollowUpConflictException();
            }
            return new ManualFollowUpResult(existing.get(), false);
        }
        FollowUpEvaluation evaluation = evaluateCustomer(customer);
        if (evaluation.status() == FollowUpStatus.INELIGIBLE) {
            throw new FollowUpConflictException();
        }
        Instant now = clock.instant();
        LocalDate today = evaluation.tenantDate();
        var context = contexts.requireCurrent();
        var result = policies.recordManualFollowUp(customer.tenantId(), customer.id(), today, expectedVersion,
                idempotencyKey, now, context.identityId(), context.membershipId(), notes);
        if (result.created()) {
            audit.followUpMutated(AuditTarget.Type.CUSTOMER, customer.id().value(),
                    AuditEventType.MANUAL_FOLLOW_UP_RECORDED);
        }
        return result;
    }

    @Transactional
    public ManualFollowUpResult recordManualFollowUp(CustomerId customerId, long expectedVersion,
            String idempotencyKey) {
        return recordManualFollowUp(customerId, expectedVersion, idempotencyKey, null);
    }

    @Transactional
    public FollowUpDismissalResult dismiss(CustomerId customerId, long expectedVersion,
            String idempotencyKey, String notes) {
        Customer customer = requireCustomerForUpdate(customerId, TenantPermission.FOLLOWUP_WRITE);
        var existing = policies.findDismissal(customer.tenantId(), idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().customerId().equals(customerId)) {
                throw new FollowUpConflictException();
            }
            return new FollowUpDismissalResult(existing.get(), false);
        }
        FollowUpEvaluation evaluation = evaluateCustomer(customer);
        if (evaluation.status() != FollowUpStatus.DUE && evaluation.status() != FollowUpStatus.OVERDUE) {
            throw new FollowUpConflictException();
        }
        Instant now = clock.instant();
        LocalDate today = evaluation.tenantDate();
        var context = contexts.requireCurrent();
        var result = policies.recordDismissal(customer.tenantId(), customer.id(), today, expectedVersion,
                idempotencyKey, now, context.identityId(), context.membershipId(), notes);
        if (result.created()) {
            audit.followUpMutated(AuditTarget.Type.CUSTOMER, customer.id().value(),
                    AuditEventType.FOLLOW_UP_DISMISSED);
        }
        return result;
    }

    @Transactional
    public CustomerFollowUpPolicy snooze(CustomerId customerId, LocalDate until, long expectedVersion) {
        Customer customer = requireCustomerForUpdate(customerId, TenantPermission.FOLLOWUP_WRITE);
        FollowUpEvaluation evaluation = evaluateCustomer(customer);
        LocalDate today = evaluation.tenantDate();
        if (Objects.requireNonNull(until, "Snooze date is required").isBefore(today)) {
            throw new IllegalArgumentException("Snooze date cannot be in the past");
        }
        if (evaluation.status() != FollowUpStatus.DUE && evaluation.status() != FollowUpStatus.OVERDUE) {
            throw new FollowUpConflictException();
        }
        var updated = policies.snooze(customer.tenantId(), customer.id(), until, expectedVersion);
        audit.followUpMutated(AuditTarget.Type.CUSTOMER, customer.id().value(), AuditEventType.FOLLOW_UP_SNOOZED);
        return updated;
    }

    private FollowUpEvaluation evaluateCustomer(Customer customer) {
        var tenantPolicy = policies.tenantPolicy(customer.tenantId());
        var customerPolicy = policies.customerPolicy(customer.tenantId(), customer.id());
        var lastPurchase = purchases.lastValid(customer.tenantId(), customer.id())
                .map(purchase -> purchase.purchasedAt()).orElse(null);
        return evaluator.evaluate(customer, contacts.find(customer), tenantPolicy, customerPolicy, lastPurchase);
    }

    private Customer requireCustomer(CustomerId customerId, TenantPermission permission) {
        authorization.requirePermission(permission);
        Customer customer = customers.findById(contexts.requireCurrent().tenantId(),
                Objects.requireNonNull(customerId, "Customer ID is required"))
                .orElseThrow(CustomerNotFoundException::new);
        authorization.requireResourceAccess(permission, customer);
        return customer;
    }

    private Customer requireCustomerForUpdate(CustomerId customerId, TenantPermission permission) {
        authorization.requirePermission(permission);
        Customer customer = customers.findByIdForUpdate(contexts.requireCurrent().tenantId(),
                Objects.requireNonNull(customerId, "Customer ID is required"))
                .orElseThrow(CustomerNotFoundException::new);
        authorization.requireResourceAccess(permission, customer);
        return customer;
    }
}
