package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpPolicyEvaluator;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.purchase.application.PurchaseRepository;
import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
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
            Clock clock,
            AiActionGateAuditListener auditListener) {
        this.customers = Objects.requireNonNull(customers, "Customer repository is required");
        this.contacts = Objects.requireNonNull(contacts, "Contact policy repository is required");
        this.policies = Objects.requireNonNull(policies, "Follow-up policy repository is required");
        this.purchases = Objects.requireNonNull(purchases, "Purchase repository is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization service is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
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
            Clock clock) {
        this(customers, contacts, policies, purchases, authorization, contexts, clock, AiActionGateAuditListener.logging());
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
        TenantContext tenantContext = tenantContextOpt.get();

        // 2. Caller Authorization
        if (!authorization.evaluate(tenantContext, TenantPermission.FOLLOWUP_EVALUATE).isAllowed()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, "MISSING_FOLLOWUP_EVALUATE_PERMISSION");
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
        if (!authorization.evaluate(tenantContext, TenantPermission.FOLLOWUP_EVALUATE, customer).isAllowed()) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.UNAUTHORIZED, "RESOURCE_ACCESS_DENIED");
            return ActionGateDecision.rejected(ActionGateRejectionReason.UNAUTHORIZED,
                    "Caller not authorized to access customer resource", null, outcome);
        }

        // Authoritative evaluation of current policies, consent, and purchases
        var tenantPolicy = policies.tenantPolicy(customer.tenantId());
        var customerPolicy = policies.customerPolicy(customer.tenantId(), customer.id());
        ContactPolicy contactPolicy = contacts.find(customer);
        Optional<Purchase> lastPurchaseOpt = purchases.lastValid(customer.tenantId(), customer.id());
        Instant lastPurchase = lastPurchaseOpt.map(Purchase::purchasedAt).orElse(null);
        PurchaseId currentLastPurchaseId = lastPurchaseOpt.map(Purchase::id).orElse(null);
        FollowUpEvaluation currentEvaluation = evaluator.evaluate(customer, contactPolicy, tenantPolicy, customerPolicy, lastPurchase);

        // 4. Customer Active State
        if (customer.status() == CustomerStatus.ARCHIVED) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.CUSTOMER_ARCHIVED, "CUSTOMER_ARCHIVED");
            return ActionGateDecision.rejected(ActionGateRejectionReason.CUSTOMER_ARCHIVED,
                    "Customer is archived", currentEvaluation, outcome);
        }

        // 5. Outcome Null Check
        if (outcome == null) {
            emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "NULL_OUTCOME");
            return ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                    "Recommendation outcome cannot be null", currentEvaluation, null);
        }

        // 6. Baseline Customer Binding (validates customer ID for any outcome when an assembly baseline is supplied)
        if (assembly != null && assembly.evaluation() != null) {
            if (!Objects.equals(assembly.evaluation().customerId(), customerId)) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "CUSTOMER_ID_MISMATCH");
                return ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        "Assembly baseline customer does not match target customer", currentEvaluation, outcome);
            }
        }

        // 7. Explicit Model Refusal
        if (outcome instanceof NoRecommendation) {
            // Model refusal is accepted once tenant context, caller authorization, customer active state, and baseline customer binding succeed
            return ActionGateDecision.accepted(outcome, currentEvaluation);
        }

        if (outcome instanceof ActionRecommendation actionRec) {
            // 8. Require assembly baseline for action recommendations
            if (assembly == null || assembly.evaluation() == null) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "MISSING_ASSEMBLY_BASELINE");
                return ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                        "Action recommendations require a valid assembly baseline", currentEvaluation, outcome);
            }

            // 9. Consent and Do-Not-Contact State (strictly for action recommendations)
            if (contactPolicy.doNotContact()) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DO_NOT_CONTACT, "DO_NOT_CONTACT_ACTIVE");
                return ActionGateDecision.rejected(ActionGateRejectionReason.DO_NOT_CONTACT,
                        "Customer has active do-not-contact restriction", currentEvaluation, outcome);
            }
            boolean eligibleContact = customer.phones().stream().anyMatch(phone -> contactPolicy.consents().stream()
                    .anyMatch(consent -> consent.contactId().equals(phone.id())
                            && consent.channel() == ContactChannel.WHATSAPP
                            && consent.status() == ConsentStatus.GRANTED));
            if (!eligibleContact) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.NO_CONTACT_CONSENT, "NO_GRANTED_WHATSAPP_CONSENT");
                return ActionGateDecision.rejected(ActionGateRejectionReason.NO_CONTACT_CONSENT,
                        "Customer lacks granted contact consent for WhatsApp", currentEvaluation, outcome);
            }

            // 10. Stale State Detection
            FollowUpEvaluation baseline = assembly.evaluation();
            boolean stale = !Objects.equals(baseline.lastPurchaseAt(), currentEvaluation.lastPurchaseAt())
                    || (assembly.lastPurchaseId() != null && !Objects.equals(assembly.lastPurchaseId(), currentLastPurchaseId))
                    || baseline.status() != currentEvaluation.status()
                    || !Objects.equals(baseline.reasons(), currentEvaluation.reasons())
                    || baseline.effectiveCadenceDays() != currentEvaluation.effectiveCadenceDays()
                    || !Objects.equals(baseline.tenantDate(), currentEvaluation.tenantDate())
                    || !Objects.equals(baseline.nextFollowUpDate(), currentEvaluation.nextFollowUpDate());
            if (stale) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.STALE_STATE, "AUTHORITATIVE_STATE_CHANGED");
                return ActionGateDecision.rejected(ActionGateRejectionReason.STALE_STATE,
                    "Authoritative state changed between context assembly and result acceptance", currentEvaluation, outcome);
            }

            // 11. Follow-Up Due State Enforcement
            if (!currentEvaluation.eligible() || (currentEvaluation.status() != FollowUpStatus.DUE
                    && currentEvaluation.status() != FollowUpStatus.OVERDUE)) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE, "CUSTOMER_NOT_DUE");
                return ActionGateDecision.rejected(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE,
                        "Customer is not currently due or overdue for follow-up", currentEvaluation, outcome);
            }

            // 10. Semantic action allowlist
            List<SemanticAction> allowedActions = assembly != null && assembly.context() != null
                    && assembly.context().trusted() != null
                    ? assembly.context().trusted().allowedActions()
                    : List.of(SemanticAction.values());

            if (allowedActions == null || !allowedActions.contains(actionRec.action())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DISALLOWED_ACTION, "ACTION_NOT_IN_ALLOWLIST");
                return ActionGateDecision.rejected(ActionGateRejectionReason.DISALLOWED_ACTION,
                        "Semantic action is not permitted for current context", currentEvaluation, outcome);
            }

            // 11. Semantic template intent allowlist and compatibility
            if (!isCompatibleIntent(actionRec.action(), actionRec.templateIntent())) {
                emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT, "INCOMPATIBLE_TEMPLATE_INTENT");
                return ActionGateDecision.rejected(ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT,
                        "Semantic template intent is incompatible with recommended action", currentEvaluation, outcome);
            }

            return ActionGateDecision.accepted(actionRec, currentEvaluation);
        }

        emitRejection(tenantContextOpt, customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION, "UNKNOWN_OUTCOME_TYPE");
        return ActionGateDecision.rejected(ActionGateRejectionReason.INVALID_RECOMMENDATION,
                "Unknown recommendation outcome type", currentEvaluation, outcome);
    }

    private void emitRejection(Optional<TenantContext> tenantContextOpt, CustomerId customerId,
            ActionGateRejectionReason reason, String diagnosticCode) {
        TenantId tenantId = tenantContextOpt.map(TenantContext::tenantId).orElse(null);
        IdentityId actorId = tenantContextOpt.map(TenantContext::identityId).orElse(null);
        auditListener.onSecurityRejection(new AiActionGateAuditListener.SecurityRejectionEvent(
                tenantId, actorId, customerId, reason, diagnosticCode, clock.instant()));
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
}
