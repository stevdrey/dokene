package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.RecommendationContext;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.DraftVariables;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.ai.domain.TrustedFollowUpReason;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.ContactConsent;
import io.github.stevdrey.dokene.customer.domain.ConsentStatus;
import io.github.stevdrey.dokene.customer.domain.ContactChannel;
import io.github.stevdrey.dokene.customer.domain.ContactIntentSource;
import io.github.stevdrey.dokene.customer.domain.ContactPolicy;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.customer.domain.CustomerStatus;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpPolicyEvaluator;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.purchase.application.PurchaseRepository;
import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAiActionGateTest {

    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final CustomerId customerId = new CustomerId(UUID.randomUUID());
    private final ZoneId zoneId = ZoneId.of("America/Costa_Rica");
    private final LocalDate tenantDate = LocalDate.of(2026, 9, 25);
    private final Instant now = Instant.parse("2026-09-25T12:00:00Z");
    private final Instant lastPurchaseTime = Instant.parse("2026-08-01T12:00:00Z");
    private final Clock clock = Clock.fixed(now, zoneId);

    private final CustomerRepository customers = mock();
    private final ContactPolicyRepository contacts = mock();
    private final FollowUpPolicyRepository policies = mock();
    private final PurchaseRepository purchases = mock();
    private final TenantAuthorizationService authorization = mock();
    private final TenantContextProvider contexts = mock();
    private final AiActionGateAuditListener auditListener = mock();

    private DefaultAiActionGate gate;

    private Customer activeCustomer;
    private CustomerPhone primaryPhone;
    private ContactPolicy validContactPolicy;
    private TenantFollowUpPolicy tenantPolicy;
    private CustomerFollowUpPolicy customerPolicy;
    private Purchase lastPurchase;
    private FollowUpEvaluation dueEvaluation;

    private final ActionRecommendation sampleAction = new ActionRecommendation(
            SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
            SemanticTemplateIntent.REPEAT_PURCHASE,
            "Customer reached cadence threshold",
            RecommendationConfidence.of(0.85),
            DraftVariables.empty());

    @BeforeEach
    void setUp() {
        gate = new DefaultAiActionGate(customers, contacts, policies, purchases,
                authorization, contexts, clock, auditListener);

        TenantContext tenantContext = new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.OPERATOR);
        when(contexts.current()).thenReturn(Optional.of(tenantContext));
        when(authorization.hasPermission(TenantPermission.FOLLOWUP_EVALUATE)).thenReturn(true);

        primaryPhone = CustomerPhone.create("+50688888888", true);
        activeCustomer = Customer.create(customerId, tenantId, "Test Customer",
                "Some internal notes", List.of(primaryPhone), now.minusSeconds(86400));
        when(customers.findById(tenantId, customerId)).thenReturn(Optional.of(activeCustomer));
        when(authorization.hasResourceAccess(eq(TenantPermission.FOLLOWUP_EVALUATE), any(Customer.class))).thenReturn(true);

        ContactConsent whatsappConsent = new ContactConsent(primaryPhone.id(), ContactChannel.WHATSAPP,
                ConsentStatus.GRANTED, ContactIntentSource.CUSTOMER_WRITTEN, now.minusSeconds(86400));
        validContactPolicy = new ContactPolicy(customerId, 1L, false, null, null, List.of(whatsappConsent));
        when(contacts.find(any(Customer.class))).thenReturn(validContactPolicy);

        tenantPolicy = new TenantFollowUpPolicy(tenantId, 30, zoneId, 1L);
        customerPolicy = new CustomerFollowUpPolicy(tenantId, customerId, 30, null, null, null, 1L);
        when(policies.tenantPolicy(tenantId)).thenReturn(tenantPolicy);
        when(policies.customerPolicy(tenantId, customerId)).thenReturn(customerPolicy);

        lastPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                lastPurchaseTime, "Purchase 1", now.minusSeconds(86400));
        when(purchases.lastValid(tenantId, customerId)).thenReturn(Optional.of(lastPurchase));

        dueEvaluation = new FollowUpPolicyEvaluator(clock).evaluate(
                activeCustomer, validContactPolicy, tenantPolicy, customerPolicy, lastPurchaseTime);
    }

    @Test
    void acceptsValidRecommendationWhenAllPolicyChecksPass() {
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
        verify(auditListener, never()).onSecurityRejection(any(), any(), any());
    }

    @Test
    void acceptsNoRecommendationRefusalWhenPolicyChecksPass() {
        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Signal unclear", RecommendationConfidence.of(0.4));
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, refusal);

        assertThat(decision.isAccepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
        verify(auditListener, never()).onSecurityRejection(any(), any(), any());
    }

    @Test
    void rejectsWhenTenantContextIsMissing() {
        when(contexts.current()).thenReturn(Optional.empty());
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.NO_TENANT_CONTEXT);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.NO_TENANT_CONTEXT), any());
    }

    @Test
    void rejectsWhenCallerLacksAuthorization() {
        when(authorization.hasPermission(TenantPermission.FOLLOWUP_EVALUATE)).thenReturn(false);
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.UNAUTHORIZED), any());
    }

    @Test
    void rejectsWhenCustomerNotFoundInTenantBoundary() {
        when(customers.findById(tenantId, customerId)).thenReturn(Optional.empty());
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.CUSTOMER_NOT_FOUND);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.CUSTOMER_NOT_FOUND), any());
    }

    @Test
    void rejectsWhenCallerLacksResourceAccessToCustomer() {
        when(authorization.hasResourceAccess(TenantPermission.FOLLOWUP_EVALUATE, activeCustomer)).thenReturn(false);
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.UNAUTHORIZED), any());
    }

    @Test
    void rejectsWhenCustomerIsArchived() {
        Customer archived = Customer.restore(customerId, tenantId, "Archived Customer",
                "notes", List.of(primaryPhone), CustomerStatus.ARCHIVED,
                now.minusSeconds(86400), now, now, 1L);
        when(customers.findById(tenantId, customerId)).thenReturn(Optional.of(archived));
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.CUSTOMER_ARCHIVED);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.CUSTOMER_ARCHIVED), any());
    }

    @Test
    void rejectsOptOutBypassWhenDoNotContactIsActive() {
        ContactPolicy dncPolicy = new ContactPolicy(customerId, 1L, true,
                ContactIntentSource.CUSTOMER_WRITTEN, now.minusSeconds(100),
                validContactPolicy.consents());
        when(contacts.find(activeCustomer)).thenReturn(dncPolicy);
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.DO_NOT_CONTACT);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.DO_NOT_CONTACT), any());
    }

    @Test
    void rejectsOptOutBypassWhenWhatsAppConsentIsNotGranted() {
        ContactConsent revokedConsent = new ContactConsent(primaryPhone.id(), ContactChannel.WHATSAPP,
                ConsentStatus.REVOKED, ContactIntentSource.CUSTOMER_WRITTEN, now.minusSeconds(3600));
        ContactPolicy revokedPolicy = new ContactPolicy(customerId, 1L, false, null, null,
                List.of(revokedConsent));
        when(contacts.find(activeCustomer)).thenReturn(revokedPolicy);
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.NO_CONTACT_CONSENT);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.NO_CONTACT_CONSENT), any());
    }

    @Test
    void rejectsWhenFollowUpIsNotDueInAuthoritativeState() {
        // Customer has no purchases -> INELIGIBLE with NO_PURCHASE_HISTORY
        when(purchases.lastValid(tenantId, customerId)).thenReturn(Optional.empty());

        ActionGateDecision decision = gate.evaluate(customerId, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE), any());
    }

    @Test
    void rejectsStaleStateWhenLastPurchaseChangedBetweenAssemblyAndAcceptance() {
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        // A new purchase was recorded while AI was thinking
        Instant newerPurchaseTime = now.minusSeconds(3600);
        Purchase newerPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                newerPurchaseTime, "Purchase 2", now);
        when(purchases.lastValid(tenantId, customerId)).thenReturn(Optional.of(newerPurchase));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.STALE_STATE), any());
    }

    @Test
    void rejectsStaleStateWhenTenantCadenceChangedBetweenAssemblyAndAcceptance() {
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        // Cadence updated from 30 to 45
        CustomerFollowUpPolicy updatedCustomerPolicy = new CustomerFollowUpPolicy(tenantId, customerId, 45, null, null, null, 2L);
        when(policies.customerPolicy(tenantId, customerId)).thenReturn(updatedCustomerPolicy);

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.STALE_STATE), any());
    }

    @Test
    void rejectsDisallowedActionNotInContextAllowlist() {
        // Allowlist only allows GENERAL_CHECK_IN, but model returned REPEAT_PURCHASE_FOLLOW_UP
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.GENERAL_CHECK_IN));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.DISALLOWED_ACTION);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.DISALLOWED_ACTION), any());
    }

    @Test
    void rejectsIncompatibleTemplateIntentForRecommendedAction() {
        // Proposing SEASONAL_EVENT for REPEAT_PURCHASE_FOLLOW_UP
        ActionRecommendation incompatibleAction = new ActionRecommendation(
                SemanticAction.REPEAT_PURCHASE_FOLLOW_UP,
                SemanticTemplateIntent.SEASONAL_EVENT,
                "Incompatible template intent test",
                RecommendationConfidence.of(0.8),
                DraftVariables.empty());
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, incompatibleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT), any());
    }

    @Test
    void rejectsNullOutcome() {
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, null);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.INVALID_RECOMMENDATION);
        verify(auditListener).onSecurityRejection(eq(customerId), eq(ActionGateRejectionReason.INVALID_RECOMMENDATION), any());
    }

    @Test
    void auditListenerExcludesCustomerNotesPromptsOrPII() {
        List<String> loggedDiagnostics = new ArrayList<>();
        AiActionGateAuditListener capturingListener = (id, reason, code) -> {
            loggedDiagnostics.add(code);
            // Verify safe identifiers only
            assertThat(code).matches("^[A-Z0-9_]{1,64}$");
        };
        DefaultAiActionGate capturingGate = new DefaultAiActionGate(customers, contacts, policies, purchases,
                authorization, contexts, clock, capturingListener);

        when(authorization.hasPermission(TenantPermission.FOLLOWUP_EVALUATE)).thenReturn(false);
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        capturingGate.evaluate(customerId, assembly, sampleAction);

        assertThat(loggedDiagnostics).containsExactly("MISSING_FOLLOWUP_EVALUATE_PERMISSION");
    }

    private RecommendationContextAssembler.Assembly assembly(FollowUpEvaluation evaluation, List<SemanticAction> allowedActions) {
        RecommendationContext.TrustedFacts trusted = new RecommendationContext.TrustedFacts(
                evaluation.tenantDate(), evaluation.status().name(),
                evaluation.reasons().stream().map(r -> TrustedFollowUpReason.valueOf(r.name())).toList(),
                evaluation.effectiveCadenceDays(), evaluation.nextFollowUpDate(), true,
                List.of(lastPurchaseTime), allowedActions);
        RecommendationContext.UntrustedText untrusted = new RecommendationContext.UntrustedText(
                "Test Customer", "Some notes", List.of("Purchase description"));
        return new RecommendationContextAssembler.Assembly(evaluation, new RecommendationContext(trusted, untrusted));
    }
}
