package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftOutcome;
import io.github.stevdrey.dokene.ai.domain.DraftGroundingContext;
import io.github.stevdrey.dokene.ai.domain.DraftSafetyValidator;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpPolicyEvaluator;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.purchase.application.PurchaseRepository;
import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic application-owned enforcement of ADR 0004 for Phase 2 recommendation and draft results.
 * Revalidates authoritative tenant context, customer status, consent, follow-up eligibility,
 * staleness, and allowlists before accepting any advisory AI outcome.
 */
@Service
public class DefaultAiActionGate implements AiActionGate {

    private final CustomerRepository customers;
    private final ContactPolicyRepository contacts;
    private final FollowUpPolicyRepository policies;
    private final PurchaseRepository purchases;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final TenantMembershipRepository memberships;
    private final TenantRepository tenants;
    private final FollowUpPolicyEvaluator evaluator;
    private final Clock clock;
    private final AiActionGateAuditListener auditListener;

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultAiActionGate(
            CustomerRepository customers,
            ContactPolicyRepository contacts,
            FollowUpPolicyRepository policies,
            PurchaseRepository purchases,
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            TenantMembershipRepository memberships,
            TenantRepository tenants,
            Clock clock,
            AiActionGateAuditListener auditListener) {
        this.customers = Objects.requireNonNull(customers, "Customer repository is required");
        this.contacts = Objects.requireNonNull(contacts, "Contact policy repository is required");
        this.policies = Objects.requireNonNull(policies, "Follow-up policy repository is required");
        this.purchases = Objects.requireNonNull(purchases, "Purchase repository is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization service is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.memberships = Objects.requireNonNull(memberships, "Tenant membership repository is required");
        this.tenants = Objects.requireNonNull(tenants, "Tenant repository is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.evaluator = new FollowUpPolicyEvaluator(clock);
        this.auditListener = Objects.requireNonNull(auditListener, "Audit listener is required");
    }

    public DefaultAiActionGate(
            CustomerRepository customers,
            ContactPolicyRepository contacts,
            FollowUpPolicyRepository policies,
            PurchaseRepository purchases,
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            TenantMembershipRepository memberships,
            TenantRepository tenants,
            Clock clock) {
        this(customers, contacts, policies, purchases, authorization, contexts, memberships, tenants, clock, AiActionGateAuditListener.logging());
    }

    @Override
    @Transactional(readOnly = true)
    public ActionGateDecision evaluate(CustomerId customerId,
                                        RecommendationContextAssembler.Assembly assembly,
                                        RecommendationOutcome outcome) {
        Objects.requireNonNull(customerId, "Customer ID is required");

        // 1. Authenticated TenantContext
        Optional<TenantContext> tenantContextOpt = contexts.current();
        if (tenantContextOpt.isEmpty()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.NO_TENANT_CONTEXT, "MISSING_TENANT_CONTEXT");
            return ActionGateDecision.rejected(ActionGateRejectionReason.NO_TENANT_CONTEXT,
                    "Active authenticated tenant context is required", null, outcome);
        }
        TenantContext cachedContext = tenantContextOpt.get();

        // Authoritative tenant status re-resolution at gate evaluation time
        Optional<Tenant> tenantOpt = tenants.findById(cachedContext.tenantId());
        if (tenantOpt.isEmpty() || tenantOpt.get().status() != TenantStatus.ACTIVE) {
            String diagnosticCode = tenantOpt
                    .map(t -> "Tenant is not active (status: " + t.status() + ")")
                    .orElse("Tenant not found");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode);
            return ActionGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Tenant is not active", null, outcome);
        }

        // Authoritative membership re-resolution at gate evaluation time
        Optional<TenantMembership> membershipOpt = memberships.findByTenantIdAndIdentityId(
                cachedContext.tenantId(), cachedContext.identityId());
        if (membershipOpt.isEmpty() || membershipOpt.get().status() != TenantMembershipStatus.ACTIVE) {
            String diagnosticCode = membershipOpt
                    .map(m -> "Tenant membership is not active (status: " + m.status() + ")")
                    .orElse("Tenant membership not found");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode);
            return ActionGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Caller membership is not active within current tenant", null, outcome);
        }
        TenantMembership currentMembership = membershipOpt.get();
        TenantContext tenantContext = new TenantContext(
                cachedContext.tenantId(),
                cachedContext.identityId(),
                currentMembership.id(),
                currentMembership.role(),
                currentMembership.status());

        // 2. Caller Authorization
        var authDecision = authorization.evaluate(tenantContext, TenantPermission.FOLLOWUP_EVALUATE);
        if (!authDecision.isAllowed()) {
            String diagnosticCode = authDecision.rejectionReason().orElse("MISSING_FOLLOWUP_EVALUATE_PERMISSION");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode);
            return ActionGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Caller lacks required permission FOLLOWUP_EVALUATE", null, outcome);
        }

        // 3. Customer Existence and Resource Ownership
        Optional<Customer> customerOpt = customers.findById(tenantContext.tenantId(), customerId);
        if (customerOpt.isEmpty()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.CUSTOMER_NOT_FOUND, "CUSTOMER_NOT_FOUND_IN_TENANT");
            return ActionGateDecision.rejected(ActionGateRejectionReason.CUSTOMER_NOT_FOUND,
                    "Customer not found within current tenant boundary", null, outcome);
        }
        Customer customer = customerOpt.get();
        var resourceDecision = authorization.evaluate(tenantContext, TenantPermission.FOLLOWUP_EVALUATE, customer);
        if (!resourceDecision.isAllowed()) {
            String diagnosticCode = resourceDecision.rejectionReason().orElse("RESOURCE_ACCESS_DENIED");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode);
            return ActionGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Caller not authorized to access customer resource", null, outcome);
        }

        // Authoritative evaluation of current policies, consent, and purchases
        var tenantPolicy = policies.tenantPolicy(customer.tenantId());
        var customerPolicy = policies.customerPolicy(customer.tenantId(), customer.id());
        long evaluatedVersion = customerPolicy.version();
        ContactPolicy contactPolicy = contacts.find(customer);
        Optional<Purchase> lastPurchaseOpt = purchases.lastValid(customer.tenantId(), customer.id());
        Instant lastPurchase = lastPurchaseOpt.map(Purchase::purchasedAt).orElse(null);
        PurchaseId currentLastPurchaseId = lastPurchaseOpt.map(Purchase::id).orElse(null);
        FollowUpEvaluation currentEvaluation = evaluator.evaluate(customer, contactPolicy, tenantPolicy, customerPolicy, lastPurchase);

        // 4. Customer Active State
        if (customer.status() == CustomerStatus.ARCHIVED) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.CUSTOMER_ARCHIVED, "CUSTOMER_ARCHIVED");
            return ActionGateDecision.rejected(ActionGateRejectionReason.CUSTOMER_ARCHIVED,
                    "Customer is archived", currentEvaluation, outcome, evaluatedVersion);
        }

        // 5. Outcome Null Check
        if (outcome == null) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "NULL_OUTCOME");
            return ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                    "Recommendation outcome cannot be null", currentEvaluation, null, evaluatedVersion);
        }

        // 6. Baseline Customer Binding (validates customer ID for any outcome when an assembly baseline is supplied)
        if (assembly != null && assembly.evaluation() != null) {
            if (!Objects.equals(assembly.evaluation().customerId(), customerId)) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "CUSTOMER_ID_MISMATCH");
                return ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        "Assembly baseline customer does not match target customer", currentEvaluation, outcome, evaluatedVersion);
            }
        }

        // 7. Explicit Model Refusal
        if (outcome instanceof NoRecommendation) {
            if (assembly != null && assembly.evaluation() != null) {
                if (isAssemblyStale(assembly, customer, currentEvaluation, currentLastPurchaseId)) {
                    emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.STALE_STATE, "AUTHORITATIVE_STATE_CHANGED");
                    return ActionGateDecision.rejected(ActionGateRejectionReason.STALE_STATE,
                            "Authoritative state changed between context assembly and result acceptance", currentEvaluation, outcome, evaluatedVersion);
                }
            }
            // Model refusal is accepted once tenant context, caller authorization, customer active state, and baseline consistency succeed
            return ActionGateDecision.accepted(outcome, currentEvaluation, evaluatedVersion);
        }

        if (outcome instanceof ActionRecommendation actionRec) {
            // 8. Require complete assembly baseline and context for action recommendations
            if (assembly == null || assembly.evaluation() == null
                    || assembly.context() == null || assembly.context().trusted() == null
                    || (assembly.evaluation().lastPurchaseAt() != null && assembly.purchases().isEmpty())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "MISSING_ASSEMBLY_BASELINE");
                return ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        "Action recommendations require a complete assembly baseline and context", currentEvaluation, outcome, evaluatedVersion);
            }

            // 9. Consent and Do-Not-Contact State (strictly for action recommendations)
            if (contactPolicy.doNotContact()) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DO_NOT_CONTACT, "DO_NOT_CONTACT_ACTIVE");
                return ActionGateDecision.rejected(ActionGateRejectionReason.DO_NOT_CONTACT,
                        "Customer has active do-not-contact restriction", currentEvaluation, outcome, evaluatedVersion);
            }
            boolean eligibleContact = customer.phones().stream().anyMatch(phone -> contactPolicy.consents().stream()
                    .anyMatch(consent -> consent.contactId().equals(phone.id())
                            && consent.channel() == ContactChannel.WHATSAPP
                            && consent.status() == ConsentStatus.GRANTED));
            if (!eligibleContact) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.NO_CONTACT_CONSENT, "NO_GRANTED_WHATSAPP_CONSENT");
                return ActionGateDecision.rejected(ActionGateRejectionReason.NO_CONTACT_CONSENT,
                        "Customer lacks granted contact consent for WhatsApp", currentEvaluation, outcome, evaluatedVersion);
            }

            // 10. Stale State Detection (for action recommendations)
            if (isAssemblyStale(assembly, customer, currentEvaluation, currentLastPurchaseId)) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.STALE_STATE, "AUTHORITATIVE_STATE_CHANGED");
                return ActionGateDecision.rejected(ActionGateRejectionReason.STALE_STATE,
                        "Authoritative state changed between context assembly and result acceptance", currentEvaluation, outcome, evaluatedVersion);
            }

            // 11. Follow-Up Due State Enforcement
            if (!currentEvaluation.eligible() || (currentEvaluation.status() != FollowUpStatus.DUE
                    && currentEvaluation.status() != FollowUpStatus.OVERDUE)) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE, "CUSTOMER_NOT_DUE");
                return ActionGateDecision.rejected(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE,
                        "Customer is not currently due or overdue for follow-up", currentEvaluation, outcome, evaluatedVersion);
            }

            // 12. Semantic action allowlist
            List<SemanticAction> allowedActions = assembly.context().trusted().allowedActions();
            if (allowedActions == null || !allowedActions.contains(actionRec.action())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DISALLOWED_ACTION, "ACTION_NOT_IN_ALLOWLIST");
                return ActionGateDecision.rejected(ActionGateRejectionReason.DISALLOWED_ACTION,
                        "Semantic action is not permitted for current context", currentEvaluation, outcome, evaluatedVersion);
            }

            // 13. Semantic template intent allowlist and compatibility
            if (!isCompatibleIntent(actionRec.action(), actionRec.templateIntent())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT, "INCOMPATIBLE_TEMPLATE_INTENT");
                return ActionGateDecision.rejected(ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT,
                        "Semantic template intent is incompatible with recommended action", currentEvaluation, outcome, evaluatedVersion);
            }

            return ActionGateDecision.accepted(actionRec, currentEvaluation, evaluatedVersion);
        }

        emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "UNKNOWN_OUTCOME_TYPE");
        return ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                "Unknown recommendation outcome type", currentEvaluation, outcome, evaluatedVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public void revalidateAuthorization(CustomerId customerId) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Optional<TenantContext> tenantContextOpt = contexts.current();
        if (tenantContextOpt.isEmpty()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.NO_TENANT_CONTEXT, "MISSING_TENANT_CONTEXT");
            throw new TenantAccessDeniedException("Active authenticated tenant context is required");
        }
        TenantContext cachedContext = tenantContextOpt.get();

        Optional<Tenant> tenantOpt = tenants.findById(cachedContext.tenantId());
        if (tenantOpt.isEmpty() || tenantOpt.get().status() != TenantStatus.ACTIVE) {
            String diagnosticCode = tenantOpt
                    .map(t -> "Tenant is not active (status: " + t.status() + ")")
                    .orElse("Tenant not found");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode);
            throw new TenantAccessDeniedException("Tenant is not active");
        }

        Optional<TenantMembership> membershipOpt = memberships.findByTenantIdAndIdentityId(
                cachedContext.tenantId(), cachedContext.identityId());
        if (membershipOpt.isEmpty() || membershipOpt.get().status() != TenantMembershipStatus.ACTIVE) {
            String diagnosticCode = membershipOpt
                    .map(m -> "Tenant membership is not active (status: " + m.status() + ")")
                    .orElse("Tenant membership not found");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode);
            throw new TenantAccessDeniedException("Caller membership is not active within current tenant");
        }
        TenantMembership currentMembership = membershipOpt.get();
        TenantContext tenantContext = new TenantContext(
                cachedContext.tenantId(),
                cachedContext.identityId(),
                currentMembership.id(),
                currentMembership.role(),
                currentMembership.status());

        var authDecision = authorization.evaluate(tenantContext, TenantPermission.FOLLOWUP_EVALUATE);
        if (!authDecision.isAllowed()) {
            String diagnosticCode = authDecision.rejectionReason().orElse("MISSING_FOLLOWUP_EVALUATE_PERMISSION");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode);
            throw new TenantAccessDeniedException("Caller lacks required permission FOLLOWUP_EVALUATE");
        }

        Optional<Customer> customerOpt = customers.findById(tenantContext.tenantId(), customerId);
        if (customerOpt.isEmpty()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.CUSTOMER_NOT_FOUND, "CUSTOMER_NOT_FOUND_IN_TENANT");
            throw new CustomerNotFoundException();
        }
        Customer customer = customerOpt.get();
        var resourceDecision = authorization.evaluate(tenantContext, TenantPermission.FOLLOWUP_EVALUATE, customer);
        if (!resourceDecision.isAllowed()) {
            String diagnosticCode = resourceDecision.rejectionReason().orElse("RESOURCE_ACCESS_DENIED");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode);
            throw new TenantAccessDeniedException("Caller not authorized to access customer resource");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DraftGateDecision evaluateDraft(CustomerId customerId,
                                           RecommendationContextAssembler.Assembly assembly,
                                           DraftOutcome outcome) {
        return evaluateDraft(customerId, assembly, outcome, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public DraftGateDecision evaluateDraft(CustomerId customerId,
                                           RecommendationContextAssembler.Assembly assembly,
                                           DraftOutcome outcome,
                                           SemanticAction expectedAction,
                                           SemanticTemplateIntent expectedIntent) {
        return evaluateDraft(customerId, assembly, outcome, expectedAction, expectedIntent, null);
    }

    @Override
    @Transactional(readOnly = true)
    public DraftGateDecision evaluateDraft(CustomerId customerId,
                                           RecommendationContextAssembler.Assembly assembly,
                                           DraftOutcome outcome,
                                           SemanticAction expectedAction,
                                           SemanticTemplateIntent expectedIntent,
                                           String expectedLocale) {
        Objects.requireNonNull(customerId, "Customer ID is required");

        // 1. Authenticated TenantContext
        Optional<TenantContext> tenantContextOpt = contexts.current();
        if (tenantContextOpt.isEmpty()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.NO_TENANT_CONTEXT, "MISSING_TENANT_CONTEXT", TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.NO_TENANT_CONTEXT,
                    "Active authenticated tenant context is required", null, outcome);
        }
        TenantContext cachedContext = tenantContextOpt.get();

        // Authoritative tenant status re-resolution
        Optional<Tenant> tenantOpt = tenants.findById(cachedContext.tenantId());
        if (tenantOpt.isEmpty() || tenantOpt.get().status() != TenantStatus.ACTIVE) {
            String diagnosticCode = tenantOpt
                    .map(t -> "Tenant is not active (status: " + t.status() + ")")
                    .orElse("Tenant not found");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Tenant is not active", null, outcome);
        }
        Tenant tenant = tenantOpt.get();

        // Authoritative membership re-resolution
        Optional<TenantMembership> membershipOpt = memberships.findByTenantIdAndIdentityId(
                cachedContext.tenantId(), cachedContext.identityId());
        if (membershipOpt.isEmpty() || membershipOpt.get().status() != TenantMembershipStatus.ACTIVE) {
            String diagnosticCode = membershipOpt
                    .map(m -> "Tenant membership is not active (status: " + m.status() + ")")
                    .orElse("Tenant membership not found");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Caller membership is not active within current tenant", null, outcome);
        }
        TenantMembership currentMembership = membershipOpt.get();
        TenantContext tenantContext = new TenantContext(
                cachedContext.tenantId(),
                cachedContext.identityId(),
                currentMembership.id(),
                currentMembership.role(),
                currentMembership.status());

        // 2. Caller Authorization for MESSAGE_DRAFT
        var authDecision = authorization.evaluate(tenantContext, TenantPermission.MESSAGE_DRAFT);
        if (!authDecision.isAllowed()) {
            String diagnosticCode = authDecision.rejectionReason().orElse("MISSING_MESSAGE_DRAFT_PERMISSION");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Caller lacks required permission MESSAGE_DRAFT", null, outcome);
        }

        // Caller Authorization for FOLLOWUP_EVALUATE
        var evalAuthDecision = authorization.evaluate(tenantContext, TenantPermission.FOLLOWUP_EVALUATE);
        if (!evalAuthDecision.isAllowed()) {
            String diagnosticCode = evalAuthDecision.rejectionReason().orElse("MISSING_FOLLOWUP_EVALUATE_PERMISSION");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Caller lacks required permission FOLLOWUP_EVALUATE", null, outcome);
        }

        // 3. Customer Existence and Resource Ownership
        Optional<Customer> customerOpt = customers.findById(tenantContext.tenantId(), customerId);
        if (customerOpt.isEmpty()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.CUSTOMER_NOT_FOUND, "CUSTOMER_NOT_FOUND_IN_TENANT", TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.CUSTOMER_NOT_FOUND,
                    "Customer not found within current tenant boundary", null, outcome);
        }
        Customer customer = customerOpt.get();
        var resourceDecision = authorization.evaluate(tenantContext, TenantPermission.MESSAGE_DRAFT, customer);
        if (!resourceDecision.isAllowed()) {
            String diagnosticCode = resourceDecision.rejectionReason().orElse("RESOURCE_ACCESS_DENIED");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Caller not authorized to access customer resource", null, outcome);
        }

        // Authoritative evaluation of current policies, consent, and purchases
        var tenantPolicy = policies.tenantPolicy(customer.tenantId());
        var customerPolicy = policies.customerPolicy(customer.tenantId(), customer.id());
        long evaluatedVersion = customerPolicy.version();
        ContactPolicy contactPolicy = contacts.find(customer);
        Optional<Purchase> lastPurchaseOpt = purchases.lastValid(customer.tenantId(), customer.id());
        Instant lastPurchase = lastPurchaseOpt.map(Purchase::purchasedAt).orElse(null);
        PurchaseId currentLastPurchaseId = lastPurchaseOpt.map(Purchase::id).orElse(null);
        FollowUpEvaluation currentEvaluation = evaluator.evaluate(customer, contactPolicy, tenantPolicy, customerPolicy, lastPurchase);

        // 4. Customer Active State
        if (customer.status() == CustomerStatus.ARCHIVED) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.CUSTOMER_ARCHIVED, "CUSTOMER_ARCHIVED", TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.CUSTOMER_ARCHIVED,
                    "Customer is archived", currentEvaluation, outcome, evaluatedVersion);
        }

        // 5. Outcome Null Check
        if (outcome == null) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "NULL_OUTCOME", TenantPermission.MESSAGE_DRAFT);
            return DraftGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                    "Draft outcome cannot be null", currentEvaluation, null, evaluatedVersion);
        }

        // 6. Baseline Customer Binding
        if (assembly != null && assembly.evaluation() != null) {
            if (!Objects.equals(assembly.evaluation().customerId(), customerId)) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "CUSTOMER_ID_MISMATCH", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        "Assembly baseline customer does not match target customer", currentEvaluation, outcome, evaluatedVersion);
            }
        }

        // 7. Explicit Model Refusal (NoDraft)
        if (outcome instanceof NoDraft) {
            if (assembly != null && assembly.evaluation() != null) {
                if (isAssemblyStale(assembly, customer, currentEvaluation, currentLastPurchaseId)) {
                    emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.STALE_STATE, "AUTHORITATIVE_STATE_CHANGED", TenantPermission.MESSAGE_DRAFT);
                    return DraftGateDecision.rejected(ActionGateRejectionReason.STALE_STATE,
                            "Authoritative state changed between context assembly and result acceptance", currentEvaluation, outcome, evaluatedVersion);
                }
            }
            if (outcome instanceof NoDraft noDraft) {
                boolean hasContext = assembly != null && assembly.context() != null;
                var refusalViolation = DraftSafetyValidator.validate(noDraft,
                        hasContext ? formatAllowedContext(assembly.context(), tenantPolicy, tenant) : "",
                        hasContext ? buildGrounding(assembly.context()) : null);
                if (refusalViolation.isPresent()) {
                    emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, refusalViolation.get().code(), TenantPermission.MESSAGE_DRAFT);
                    return DraftGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                            refusalViolation.get().description(), currentEvaluation, outcome, evaluatedVersion);
                }
            }
            return DraftGateDecision.accepted(outcome, currentEvaluation, evaluatedVersion);
        }

        if (outcome instanceof MessageDraft draft) {
            // 8. Require complete assembly baseline and context for draft
            if (assembly == null || assembly.evaluation() == null
                    || assembly.context() == null || assembly.context().trusted() == null
                    || (assembly.evaluation().lastPurchaseAt() != null && assembly.purchases().isEmpty())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "MISSING_ASSEMBLY_BASELINE", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        "Message drafts require a complete assembly baseline and context", currentEvaluation, outcome, evaluatedVersion);
            }

            // 9. Consent and Do-Not-Contact State
            if (contactPolicy.doNotContact()) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DO_NOT_CONTACT, "DO_NOT_CONTACT_ACTIVE", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.DO_NOT_CONTACT,
                        "Customer has active do-not-contact restriction", currentEvaluation, outcome, evaluatedVersion);
            }
            boolean eligibleContact = customer.phones().stream().anyMatch(phone -> contactPolicy.consents().stream()
                    .anyMatch(consent -> consent.contactId().equals(phone.id())
                            && consent.channel() == ContactChannel.WHATSAPP
                            && consent.status() == ConsentStatus.GRANTED));
            if (!eligibleContact) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.NO_CONTACT_CONSENT, "NO_GRANTED_WHATSAPP_CONSENT", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.NO_CONTACT_CONSENT,
                        "Customer lacks granted contact consent for WhatsApp", currentEvaluation, outcome, evaluatedVersion);
            }

            // 10. Stale State Detection
            if (isAssemblyStale(assembly, customer, currentEvaluation, currentLastPurchaseId)) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.STALE_STATE, "AUTHORITATIVE_STATE_CHANGED", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.STALE_STATE,
                        "Authoritative state changed between context assembly and result acceptance", currentEvaluation, outcome, evaluatedVersion);
            }

            // 11. Follow-Up Due State Enforcement
            if (!currentEvaluation.eligible() || (currentEvaluation.status() != FollowUpStatus.DUE
                    && currentEvaluation.status() != FollowUpStatus.OVERDUE)) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE, "CUSTOMER_NOT_DUE", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE,
                        "Customer is not currently due or overdue for follow-up", currentEvaluation, outcome, evaluatedVersion);
            }

            // 12. Check expected action match
            if (expectedAction != null && draft.action() != expectedAction) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DISALLOWED_ACTION, "ACTION_MISMATCH_WITH_REQUEST", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.DISALLOWED_ACTION,
                        "Draft action does not match requested action", currentEvaluation, outcome, evaluatedVersion);
            }

            // Check expected template intent match
            if (expectedIntent != null && draft.templateIntent() != expectedIntent) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT, "TEMPLATE_INTENT_MISMATCH_WITH_REQUEST", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT,
                        "Draft template intent does not match requested template intent", currentEvaluation, outcome, evaluatedVersion);
            }

            // Check expected locale match
            if (expectedLocale != null && draft.locale() != null
                    && !draft.locale().trim().equalsIgnoreCase(expectedLocale.trim())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "LOCALE_MISMATCH", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        "Draft locale does not match requested locale: expected '" + expectedLocale + "', got '" + draft.locale() + "'", currentEvaluation, outcome, evaluatedVersion);
            }

            // 13. Semantic action allowlist
            List<SemanticAction> allowedActions = assembly.context().trusted().allowedActions();
            if (allowedActions == null || !allowedActions.contains(draft.action())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DISALLOWED_ACTION, "ACTION_NOT_IN_ALLOWLIST", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.DISALLOWED_ACTION,
                        "Semantic action is not permitted for current context", currentEvaluation, outcome, evaluatedVersion);
            }

            // 14. Semantic template intent allowlist and compatibility
            if (!isCompatibleIntent(draft.action(), draft.templateIntent())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT, "INCOMPATIBLE_TEMPLATE_INTENT", TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT,
                        "Semantic template intent is incompatible with recommended action", currentEvaluation, outcome, evaluatedVersion);
            }

            // 15. Safety and grounding validation
            String allowedContext = formatAllowedContext(assembly.context(), tenantPolicy, tenant);
            var grounding = buildGrounding(assembly.context());
            var violation = DraftSafetyValidator.validate(draft, allowedContext, grounding);
            if (violation.isPresent()) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, violation.get().code(), TenantPermission.MESSAGE_DRAFT);
                return DraftGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        violation.get().description(), currentEvaluation, outcome, evaluatedVersion);
            }

            return DraftGateDecision.accepted(draft, currentEvaluation, evaluatedVersion);
        }

        emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "UNKNOWN_OUTCOME_TYPE", TenantPermission.MESSAGE_DRAFT);
        return DraftGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                "Unknown draft outcome type", currentEvaluation, outcome, evaluatedVersion);
    }

    @Override
    @Transactional(readOnly = true)
    public void revalidateDraftAuthorization(CustomerId customerId) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Optional<TenantContext> tenantContextOpt = contexts.current();
        if (tenantContextOpt.isEmpty()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.NO_TENANT_CONTEXT, "MISSING_TENANT_CONTEXT", TenantPermission.MESSAGE_DRAFT);
            throw new TenantAccessDeniedException("Active authenticated tenant context is required");
        }
        TenantContext cachedContext = tenantContextOpt.get();

        Optional<Tenant> tenantOpt = tenants.findById(cachedContext.tenantId());
        if (tenantOpt.isEmpty() || tenantOpt.get().status() != TenantStatus.ACTIVE) {
            String diagnosticCode = tenantOpt
                    .map(t -> "Tenant is not active (status: " + t.status() + ")")
                    .orElse("Tenant not found");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            throw new TenantAccessDeniedException("Tenant is not active");
        }

        Optional<TenantMembership> membershipOpt = memberships.findByTenantIdAndIdentityId(
                cachedContext.tenantId(), cachedContext.identityId());
        if (membershipOpt.isEmpty() || membershipOpt.get().status() != TenantMembershipStatus.ACTIVE) {
            String diagnosticCode = membershipOpt
                    .map(m -> "Tenant membership is not active (status: " + m.status() + ")")
                    .orElse("Tenant membership not found");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            throw new TenantAccessDeniedException("Caller membership is not active within current tenant");
        }
        TenantMembership currentMembership = membershipOpt.get();
        TenantContext tenantContext = new TenantContext(
                cachedContext.tenantId(),
                cachedContext.identityId(),
                currentMembership.id(),
                currentMembership.role(),
                currentMembership.status());

        var authDecision = authorization.evaluate(tenantContext, TenantPermission.MESSAGE_DRAFT);
        if (!authDecision.isAllowed()) {
            String diagnosticCode = authDecision.rejectionReason().orElse("MISSING_MESSAGE_DRAFT_PERMISSION");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            throw new TenantAccessDeniedException("Caller lacks required permission MESSAGE_DRAFT");
        }

        var evalAuthDecision = authorization.evaluate(tenantContext, TenantPermission.FOLLOWUP_EVALUATE);
        if (!evalAuthDecision.isAllowed()) {
            String diagnosticCode = evalAuthDecision.rejectionReason().orElse("MISSING_FOLLOWUP_EVALUATE_PERMISSION");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            throw new TenantAccessDeniedException("Caller lacks required permission FOLLOWUP_EVALUATE");
        }

        Optional<Customer> customerOpt = customers.findById(tenantContext.tenantId(), customerId);
        if (customerOpt.isEmpty()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.CUSTOMER_NOT_FOUND, "CUSTOMER_NOT_FOUND_IN_TENANT", TenantPermission.MESSAGE_DRAFT);
            throw new CustomerNotFoundException();
        }
        Customer customer = customerOpt.get();
        var resourceDecision = authorization.evaluate(tenantContext, TenantPermission.MESSAGE_DRAFT, customer);
        if (!resourceDecision.isAllowed()) {
            String diagnosticCode = resourceDecision.rejectionReason().orElse("RESOURCE_ACCESS_DENIED");
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, diagnosticCode, TenantPermission.MESSAGE_DRAFT);
            throw new TenantAccessDeniedException("Caller not authorized to access customer resource");
        }
    }

    private void emitRejection(Optional<TenantContext> tenantContextOpt, CustomerId customerId,
            ActionGateRejectionReason reason, String diagnosticCode) {
        emitRejection(tenantContextOpt, customerId, reason, diagnosticCode, TenantPermission.FOLLOWUP_EVALUATE);
    }

    private void emitRejection(Optional<TenantContext> tenantContextOpt, CustomerId customerId,
            ActionGateRejectionReason reason, String diagnosticCode, TenantPermission permission) {
        TenantId tenantId = tenantContextOpt.map(TenantContext::tenantId).orElse(null);
        IdentityId actorId = tenantContextOpt.map(TenantContext::identityId).orElse(null);
        auditListener.onSecurityRejection(new AiActionGateAuditListener.SecurityRejectionEvent(
                tenantId, actorId, customerId, reason, diagnosticCode, clock.instant(), permission));
    }

    private static DraftGroundingContext buildGrounding(RecommendationContext context) {
        var untrustedText = context.untrusted();
        return new DraftGroundingContext(
                untrustedText.displayName(), untrustedText.notes(), untrustedText.purchaseDescriptions(),
                context.trusted().purchaseDates().stream().map(Object::toString).toList(),
                context.trusted().followUpStatus(),
                context.trusted().tenantDate().toString());
    }

    private String formatAllowedContext(RecommendationContext context, TenantFollowUpPolicy tenantPolicy) {
        return formatAllowedContext(context, tenantPolicy, null);
    }

    private String formatAllowedContext(RecommendationContext context, TenantFollowUpPolicy tenantPolicy, Tenant tenant) {
        if (context == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (tenant != null && tenant.displayName() != null) {
            sb.append(tenant.displayName()).append(" ");
        }
        if (context.trusted() != null) {
            sb.append(context.trusted().tenantDate()).append(" ");
            sb.append(context.trusted().followUpStatus()).append(" ");
            for (var purchaseDate : context.trusted().purchaseDates()) {
                sb.append(purchaseDate).append(" ");
            }
        }
        if (context.untrusted() != null) {
            sb.append(context.untrusted().displayName()).append(" ");
            if (context.untrusted().notes() != null) {
                sb.append(context.untrusted().notes()).append(" ");
            }
            if (context.untrusted().purchaseDescriptions() != null) {
                for (String desc : context.untrusted().purchaseDescriptions()) {
                    sb.append(desc).append(" ");
                }
            }
        }
        if (tenantPolicy != null && tenantPolicy.zoneId() != null) {
            sb.append(tenantPolicy.zoneId().getId()).append(" ");
        }
        return sb.toString();
    }

    private boolean isCompatibleIntent(SemanticAction action, SemanticTemplateIntent intent) {
        if (action == null || intent == null) {
            return false;
        }
        return switch (action) {
            case REPEAT_PURCHASE_FOLLOW_UP -> intent == SemanticTemplateIntent.REPEAT_PURCHASE
                    || intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case GENERAL_CHECK_IN -> intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case RELATED_PRODUCT_OFFER -> intent == SemanticTemplateIntent.RELATED_PRODUCT
                    || intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case DORMANT_REENGAGEMENT -> intent == SemanticTemplateIntent.DORMANT_CUSTOMER
                    || intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case SEASONAL_GREETING -> intent == SemanticTemplateIntent.SEASONAL_EVENT
                    || intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
        };
    }

    private ActionGateRejectionReason deriveIneligibilityReason(FollowUpEvaluation evaluation) {
        if (evaluation != null && evaluation.reasons() != null) {
            if (evaluation.reasons().contains(FollowUpReason.DO_NOT_CONTACT)) {
                return ActionGateRejectionReason.DO_NOT_CONTACT;
            }
            if (evaluation.reasons().contains(FollowUpReason.NO_ELIGIBLE_CONTACT)) {
                return ActionGateRejectionReason.NO_CONTACT_CONSENT;
            }
            if (evaluation.reasons().contains(FollowUpReason.CUSTOMER_ARCHIVED)) {
                return ActionGateRejectionReason.CUSTOMER_ARCHIVED;
            }
        }
        return ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE;
    }

    private boolean isAssemblyStale(
            RecommendationContextAssembler.Assembly assembly,
            Customer customer,
            FollowUpEvaluation currentEvaluation,
            PurchaseId currentLastPurchaseId) {
        FollowUpEvaluation baseline = assembly.evaluation();
        PurchaseId baselineLastPurchaseId = assembly.lastPurchaseId();
        if (!Objects.equals(baseline.lastPurchaseAt(), currentEvaluation.lastPurchaseAt())
                || !Objects.equals(baselineLastPurchaseId, currentLastPurchaseId)
                || baseline.status() != currentEvaluation.status()
                || !Objects.equals(baseline.reasons(), currentEvaluation.reasons())
                || baseline.effectiveCadenceDays() != currentEvaluation.effectiveCadenceDays()
                || !Objects.equals(baseline.tenantDate(), currentEvaluation.tenantDate())
                || !Objects.equals(baseline.nextFollowUpDate(), currentEvaluation.nextFollowUpDate())) {
            return true;
        }

        if (assembly.context() != null && assembly.context().untrusted() != null) {
            RecommendationContext.UntrustedText untrusted = assembly.context().untrusted();
            if (!Objects.equals(untrusted.displayName(), customer.displayName())
                    || !Objects.equals(untrusted.notes(), customer.notes())) {
                return true;
            }
        }

        List<Purchase> currentPurchases = purchases.list(
                customer.tenantId(), customer.id(), PurchaseStatus.VALID, null, RecommendationContext.MAX_PURCHASES);
        List<PurchaseBaseline> baselinePurchases = assembly.purchases();
        if (currentPurchases.size() != baselinePurchases.size()) {
            return true;
        }

        for (int i = 0; i < currentPurchases.size(); i++) {
            Purchase current = currentPurchases.get(i);
            PurchaseBaseline pb = baselinePurchases.get(i);
            if (!Objects.equals(current.id(), pb.id())
                    || current.version() != pb.version()
                    || !Objects.equals(current.purchasedAt(), pb.purchasedAt())
                    || current.status() != PurchaseStatus.VALID) {
                return true;
            }
        }
        return false;
    }
}
