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
import io.github.stevdrey.dokene.tenant.application.AuthorizationDecision;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembership;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipStatus;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
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
    private final TenantMembershipRepository memberships = mock();
    private final TenantRepository tenants = mock();
    private final AiActionGateAuditListener auditListener = mock();

    private DefaultAiActionGate gate;

    private TenantContext tenantContext;
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
                authorization, contexts, memberships, tenants, clock, auditListener);

        tenantContext = new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
                new TenantMembershipId(UUID.randomUUID()), TenantRole.OPERATOR);
        when(contexts.current()).thenReturn(Optional.of(tenantContext));

        Tenant activeTenant = Tenant.create(tenantId, "Test Tenant", now.minusSeconds(86400));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(activeTenant));

        TenantMembership activeMembership = TenantMembership.createActive(
                tenantContext.membershipId(), tenantId, tenantContext.identityId(),
                TenantRole.OPERATOR, now.minusSeconds(86400));
        when(memberships.findByTenantIdAndIdentityId(tenantId, tenantContext.identityId()))
                .thenReturn(Optional.of(activeMembership));

        when(authorization.evaluate(eq(tenantContext), eq(TenantPermission.FOLLOWUP_EVALUATE)))
                .thenReturn(AuthorizationDecision.allow());

        primaryPhone = CustomerPhone.create("+50688888888", true);
        activeCustomer = Customer.create(customerId, tenantId, "Test Customer",
                "Some internal notes", List.of(primaryPhone), now.minusSeconds(86400));
        when(customers.findById(tenantId, customerId)).thenReturn(Optional.of(activeCustomer));
        when(authorization.evaluate(eq(tenantContext), eq(TenantPermission.FOLLOWUP_EVALUATE), any(Customer.class)))
                .thenReturn(AuthorizationDecision.allow());

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
        when(purchases.findById(tenantId, customerId, lastPurchase.id())).thenReturn(Optional.of(lastPurchase));

        dueEvaluation = new FollowUpPolicyEvaluator(clock).evaluate(
                activeCustomer, validContactPolicy, tenantPolicy, customerPolicy, lastPurchaseTime);
    }

    @Test
    void acceptsValidRecommendationWhenAllPolicyChecksPass() {
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
        verify(auditListener, never()).onSecurityRejection(any());
    }

    @Test
    void acceptsNoRecommendationRefusalWhenPolicyChecksPass() {
        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Signal unclear", RecommendationConfidence.of(0.4));
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, refusal);

        assertThat(decision.isAccepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
        verify(auditListener, never()).onSecurityRejection(any());
    }

    @Test
    void preservesRefusalWhenCustomerNotDue() {
        // Customer has no purchases -> NOT_YET_DUE or INELIGIBLE, but refusal is preserved
        when(purchases.lastValid(tenantId, customerId)).thenReturn(Optional.empty());
        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.INSUFFICIENT_HISTORY,
                "No purchase history", RecommendationConfidence.of(0.9));

        ActionGateDecision decision = gate.evaluate(customerId, refusal);

        assertThat(decision.isAccepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
        verify(auditListener, never()).onSecurityRejection(any());
    }

    @Test
    void acceptsRefusalEvenWhenCustomerLacksContactConsentOrHasDnc() {
        ContactPolicy dncPolicy = new ContactPolicy(customerId, 1L, true,
                ContactIntentSource.CUSTOMER_WRITTEN, now.minusSeconds(100),
                List.of());
        when(contacts.find(activeCustomer)).thenReturn(dncPolicy);
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));
        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Signal unclear", RecommendationConfidence.of(0.4));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, refusal);

        assertThat(decision.isAccepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
        assertThat(decision.evaluation()).isPresent();
        assertThat(decision.evaluation().get().status()).isEqualTo(FollowUpStatus.INELIGIBLE);
        assertThat(decision.evaluation().get().reasons()).contains(FollowUpReason.DO_NOT_CONTACT);
        verify(auditListener, never()).onSecurityRejection(any());
    }

    @Test
    void acceptsRefusalEvenWhenStateIsStale() {
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        // A new purchase was recorded while AI was thinking
        Instant newerPurchaseTime = now.minusSeconds(3600);
        Purchase newerPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                newerPurchaseTime, "Purchase 2", now);
        when(purchases.lastValid(tenantId, customerId)).thenReturn(Optional.of(newerPurchase));

        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Signal unclear", RecommendationConfidence.of(0.4));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, refusal);

        assertThat(decision.isAccepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
        verify(auditListener, never()).onSecurityRejection(any());
    }

    @Test
    void rejectsWhenTenantContextIsMissing() {
        when(contexts.current()).thenReturn(Optional.empty());
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.NO_TENANT_CONTEXT);
        verifyRejection(customerId, ActionGateRejectionReason.NO_TENANT_CONTEXT);
    }

    @Test
    void rejectsWhenCallerLacksAuthorization() {
        when(authorization.evaluate(eq(tenantContext), eq(TenantPermission.FOLLOWUP_EVALUATE)))
                .thenReturn(AuthorizationDecision.deny("Denied"));
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        verifyRejection(customerId, ActionGateRejectionReason.UNAUTHORIZED);
    }

    @Test
    void rejectsWhenCustomerNotFoundInTenantBoundary() {
        when(customers.findById(tenantId, customerId)).thenReturn(Optional.empty());
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.CUSTOMER_NOT_FOUND);
        verifyRejection(customerId, ActionGateRejectionReason.CUSTOMER_NOT_FOUND);
    }

    @Test
    void rejectsWhenCallerLacksResourceAccessToCustomer() {
        when(authorization.evaluate(eq(tenantContext), eq(TenantPermission.FOLLOWUP_EVALUATE), eq(activeCustomer)))
                .thenReturn(AuthorizationDecision.deny("Resource access denied"));
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        verifyRejection(customerId, ActionGateRejectionReason.UNAUTHORIZED);
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
        assertThat(decision.evaluation()).isPresent();
        assertThat(decision.evaluation().get().status()).isEqualTo(FollowUpStatus.INELIGIBLE);
        assertThat(decision.evaluation().get().reasons()).contains(FollowUpReason.CUSTOMER_ARCHIVED);
        verifyRejection(customerId, ActionGateRejectionReason.CUSTOMER_ARCHIVED);
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
        assertThat(decision.evaluation()).isPresent();
        assertThat(decision.evaluation().get().status()).isEqualTo(FollowUpStatus.INELIGIBLE);
        assertThat(decision.evaluation().get().reasons()).contains(FollowUpReason.DO_NOT_CONTACT);
        verifyRejection(customerId, ActionGateRejectionReason.DO_NOT_CONTACT);
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
        assertThat(decision.evaluation()).isPresent();
        assertThat(decision.evaluation().get().status()).isEqualTo(FollowUpStatus.INELIGIBLE);
        assertThat(decision.evaluation().get().reasons()).contains(FollowUpReason.NO_ELIGIBLE_CONTACT);
        verifyRejection(customerId, ActionGateRejectionReason.NO_CONTACT_CONSENT);
    }

    @Test
    void rejectsWhenFollowUpIsNotDueInAuthoritativeState() {
        // Customer has no purchases -> INELIGIBLE with NO_PURCHASE_HISTORY
        when(purchases.lastValid(tenantId, customerId)).thenReturn(Optional.empty());
        FollowUpEvaluation notDueEvaluation = new FollowUpPolicyEvaluator(clock).evaluate(
                activeCustomer, validContactPolicy, tenantPolicy, customerPolicy, null);

        // Assembly provided with matching notDueEvaluation and a context containing allowed actions
        RecommendationContext.TrustedFacts trusted = new RecommendationContext.TrustedFacts(
                tenantDate, "DUE", List.of(TrustedFollowUpReason.DUE_TODAY),
                30, tenantDate, true, List.of(), List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));
        RecommendationContext.UntrustedText untrusted = new RecommendationContext.UntrustedText(
                "Test Customer", "Notes", List.of());
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                notDueEvaluation, new RecommendationContext(trusted, untrusted), List.of());

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE);
        verifyRejection(customerId, ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE);
    }

    @Test
    void rejectsActionRecommendationWhenAssemblyBaselineIsMissing() {
        ActionGateDecision decision = gate.evaluate(customerId, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.INVALID_RECOMMENDATION);
        verify(auditListener).onSecurityRejection(argThat(event ->
                Objects.equals(event.customerId(), customerId)
                        && event.reason() == ActionGateRejectionReason.INVALID_RECOMMENDATION
                        && "MISSING_ASSEMBLY_BASELINE".equals(event.diagnosticCode())));
    }

    @Test
    void rejectsActionRecommendationWhenAssemblyContextIsMissing() {
        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                dueEvaluation, null, List.of(PurchaseBaseline.from(lastPurchase)));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.INVALID_RECOMMENDATION);
        verify(auditListener).onSecurityRejection(argThat(event ->
                Objects.equals(event.customerId(), customerId)
                        && event.reason() == ActionGateRejectionReason.INVALID_RECOMMENDATION
                        && "MISSING_ASSEMBLY_BASELINE".equals(event.diagnosticCode())));
    }

    @Test
    void rejectsRefusalWhenAssemblyCustomerIdDoesNotMatchTargetCustomer() {
        CustomerId otherCustomer = new CustomerId(UUID.randomUUID());
        Customer otherCustomerEntity = Customer.create(otherCustomer, tenantId, "Other Customer", "Notes", List.of(primaryPhone), now.minusSeconds(86400));
        ContactPolicy otherContactPolicy = new ContactPolicy(otherCustomer, 1L, false, null, null, validContactPolicy.consents());
        CustomerFollowUpPolicy otherCustomerPolicy = new CustomerFollowUpPolicy(tenantId, otherCustomer, 30, null, null, null, 1L);
        FollowUpEvaluation otherEvaluation = new FollowUpPolicyEvaluator(clock).evaluate(
                otherCustomerEntity, otherContactPolicy, tenantPolicy, otherCustomerPolicy, lastPurchaseTime);
        RecommendationContextAssembler.Assembly assembly = assembly(otherEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        NoRecommendation refusal = new NoRecommendation(NoRecommendationReason.UNCERTAIN_INTENT,
                "Signal unclear", RecommendationConfidence.of(0.4));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, refusal);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.INVALID_RECOMMENDATION);
        verify(auditListener).onSecurityRejection(argThat(event ->
                Objects.equals(event.customerId(), customerId)
                        && event.reason() == ActionGateRejectionReason.INVALID_RECOMMENDATION
                        && "CUSTOMER_ID_MISMATCH".equals(event.diagnosticCode())));
    }

    @Test
    void rejectsWhenAssemblyCustomerIdDoesNotMatchTargetCustomer() {
        CustomerId otherCustomer = new CustomerId(UUID.randomUUID());
        Customer otherCustomerEntity = Customer.create(otherCustomer, tenantId, "Other Customer", "Notes", List.of(primaryPhone), now.minusSeconds(86400));
        ContactPolicy otherContactPolicy = new ContactPolicy(otherCustomer, 1L, false, null, null, validContactPolicy.consents());
        CustomerFollowUpPolicy otherCustomerPolicy = new CustomerFollowUpPolicy(tenantId, otherCustomer, 30, null, null, null, 1L);
        FollowUpEvaluation otherEvaluation = new FollowUpPolicyEvaluator(clock).evaluate(
                otherCustomerEntity, otherContactPolicy, tenantPolicy, otherCustomerPolicy, lastPurchaseTime);
        RecommendationContextAssembler.Assembly assembly = assembly(otherEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.INVALID_RECOMMENDATION);
        verify(auditListener).onSecurityRejection(argThat(event ->
                Objects.equals(event.customerId(), customerId)
                        && event.reason() == ActionGateRejectionReason.INVALID_RECOMMENDATION
                        && "CUSTOMER_ID_MISMATCH".equals(event.diagnosticCode())));
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
        verifyRejection(customerId, ActionGateRejectionReason.STALE_STATE);
    }

    @Test
    void rejectsStaleStateWhenPurchaseIdChangedEvenWithSameTimestamp() {
        PurchaseId originalPurchaseId = lastPurchase.id();
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation,
                List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP), originalPurchaseId);

        // New purchase with SAME timestamp but different PurchaseId
        Purchase tiedPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                lastPurchaseTime, "Tied timestamp purchase", now);
        when(purchases.lastValid(tenantId, customerId)).thenReturn(Optional.of(tiedPurchase));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
        verifyRejection(customerId, ActionGateRejectionReason.STALE_STATE);
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
        verifyRejection(customerId, ActionGateRejectionReason.STALE_STATE);
    }

    @Test
    void rejectsDisallowedActionNotInContextAllowlist() {
        // Allowlist only allows GENERAL_CHECK_IN, but model returned REPEAT_PURCHASE_FOLLOW_UP
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.GENERAL_CHECK_IN));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.DISALLOWED_ACTION);
        verifyRejection(customerId, ActionGateRejectionReason.DISALLOWED_ACTION);
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
        verifyRejection(customerId, ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT);
    }

    @Test
    void rejectedDecisionRetainsRawOutcomeForDownstreamDiagnostics() {
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.GENERAL_CHECK_IN));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.DISALLOWED_ACTION);
        assertThat(decision.rawOutcome()).contains(sampleAction);
    }

    @Test
    void rejectsNullOutcome() {
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, null);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.INVALID_RECOMMENDATION);
        verifyRejection(customerId, ActionGateRejectionReason.INVALID_RECOMMENDATION);
    }

    @Test
    void auditListenerExcludesCustomerNotesPromptsOrPII() {
        List<AiActionGateAuditListener.SecurityRejectionEvent> events = new ArrayList<>();
        AiActionGateAuditListener capturingListener = events::add;
        DefaultAiActionGate capturingGate = new DefaultAiActionGate(customers, contacts, policies, purchases,
                authorization, contexts, memberships, tenants, clock, capturingListener);

        when(authorization.evaluate(eq(tenantContext), eq(TenantPermission.FOLLOWUP_EVALUATE)))
                .thenReturn(AuthorizationDecision.deny("Role OPERATOR lacks permission FOLLOWUP_EVALUATE"));
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        capturingGate.evaluate(customerId, assembly, sampleAction);

        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.tenantId()).isEqualTo(tenantId);
        assertThat(event.actorId()).isNotNull();
        assertThat(event.customerId()).isEqualTo(customerId);
        assertThat(event.reason()).isEqualTo(ActionGateRejectionReason.UNAUTHORIZED);
        assertThat(event.diagnosticCode()).isEqualTo("Role OPERATOR lacks permission FOLLOWUP_EVALUATE");
        assertThat(event.timestamp()).isEqualTo(now);
    }

    @Test
    void rejectsWhenMembershipRevokedInFlight() {
        when(memberships.findByTenantIdAndIdentityId(tenantId, tenantContext.identityId()))
                .thenReturn(Optional.empty());
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        verify(auditListener).onSecurityRejection(argThat(event ->
                Objects.equals(event.customerId(), customerId)
                        && event.reason() == ActionGateRejectionReason.UNAUTHORIZED
                        && "Tenant membership not found".equals(event.diagnosticCode())));
    }

    @Test
    void rejectsWhenMembershipSuspendedInFlight() {
        TenantMembership suspendedMembership = TenantMembership.createActive(
                tenantContext.membershipId(), tenantId, tenantContext.identityId(),
                TenantRole.OPERATOR, now.minusSeconds(86400));
        suspendedMembership.suspend(now.minusSeconds(10));
        when(memberships.findByTenantIdAndIdentityId(tenantId, tenantContext.identityId()))
                .thenReturn(Optional.of(suspendedMembership));
        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        verify(auditListener).onSecurityRejection(argThat(event ->
                Objects.equals(event.customerId(), customerId)
                        && event.reason() == ActionGateRejectionReason.UNAUTHORIZED
                        && "Tenant membership is not active (status: SUSPENDED)".equals(event.diagnosticCode())));
    }

    @Test
    void rejectsWhenMembershipRoleDowngradedInFlight() {
        TenantMembership downgradedMembership = TenantMembership.createActive(
                tenantContext.membershipId(), tenantId, tenantContext.identityId(),
                TenantRole.OPERATOR, now.minusSeconds(86400));
        downgradedMembership.changeRole(TenantRole.VIEWER, now.minusSeconds(10));
        when(memberships.findByTenantIdAndIdentityId(tenantId, tenantContext.identityId()))
                .thenReturn(Optional.of(downgradedMembership));

        TenantContext viewerContext = new TenantContext(tenantId, tenantContext.identityId(),
                tenantContext.membershipId(), TenantRole.VIEWER, TenantMembershipStatus.ACTIVE);
        when(authorization.evaluate(eq(viewerContext), eq(TenantPermission.FOLLOWUP_EVALUATE)))
                .thenReturn(AuthorizationDecision.deny("Role VIEWER lacks permission FOLLOWUP_EVALUATE"));

        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        verify(auditListener).onSecurityRejection(argThat(event ->
                Objects.equals(event.customerId(), customerId)
                        && event.reason() == ActionGateRejectionReason.UNAUTHORIZED
                        && "Role VIEWER lacks permission FOLLOWUP_EVALUATE".equals(event.diagnosticCode())));
    }

    @Test
    void rejectsAssemblyConstructionWhenEvaluationHasPurchaseHistoryAndPurchaseBaselineIsEmpty() {
        RecommendationContext context = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)).context();

        assertThatThrownBy(() -> new RecommendationContextAssembler.Assembly(dueEvaluation, context, (List<PurchaseBaseline>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Purchase baseline is required when baseline contains purchase history");

        assertThatThrownBy(() -> new RecommendationContextAssembler.Assembly(dueEvaluation, context, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Purchase baseline is required when baseline contains purchase history");
    }

    @Test
    void rejectsWhenTenantSuspendedInFlight() {
        Tenant suspendedTenant = Tenant.create(tenantId, "Suspended Tenant", now.minusSeconds(86400));
        suspendedTenant.suspend(now.minusSeconds(3600));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(suspendedTenant));

        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));
        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        assertThat(decision.diagnostic()).contains("Tenant is not active");
        verify(auditListener).onSecurityRejection(argThat(event ->
                event.reason() == ActionGateRejectionReason.UNAUTHORIZED
                        && event.diagnosticCode().equals("Tenant is not active (status: SUSPENDED)")
                        && event.tenantId().equals(tenantId)
        ));
    }

    @Test
    void rejectsWhenTenantArchivedInFlight() {
        Tenant archivedTenant = Tenant.create(tenantId, "Archived Tenant", now.minusSeconds(86400));
        archivedTenant.archive(now.minusSeconds(3600));
        when(tenants.findById(tenantId)).thenReturn(Optional.of(archivedTenant));

        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));
        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        assertThat(decision.diagnostic()).contains("Tenant is not active");
        verify(auditListener).onSecurityRejection(argThat(event ->
                event.reason() == ActionGateRejectionReason.UNAUTHORIZED
                        && event.diagnosticCode().equals("Tenant is not active (status: ARCHIVED)")
                        && event.tenantId().equals(tenantId)
        ));
    }

    @Test
    void rejectsWhenTenantNotFoundInFlight() {
        when(tenants.findById(tenantId)).thenReturn(Optional.empty());

        RecommendationContextAssembler.Assembly assembly = assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP));
        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.UNAUTHORIZED);
        assertThat(decision.diagnostic()).contains("Tenant is not active");
        verify(auditListener).onSecurityRejection(argThat(event ->
                event.reason() == ActionGateRejectionReason.UNAUTHORIZED
                        && event.diagnosticCode().equals("Tenant not found")
                        && event.tenantId().equals(tenantId)
        ));
    }

    @Test
    void rejectsWhenNonLatestPurchaseInBaselineWasCorrectedInFlight() {
        Purchase secondPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                lastPurchaseTime.minusSeconds(3600), "Purchase 2", now.minusSeconds(86400));
        PurchaseBaseline latestBaseline = PurchaseBaseline.from(lastPurchase);
        PurchaseBaseline secondBaseline = PurchaseBaseline.from(secondPurchase);

        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                dueEvaluation,
                assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)).context(),
                List.of(latestBaseline, secondBaseline));

        Purchase correctedSecondPurchase = Purchase.restore(
                secondPurchase.id(), tenantId, customerId,
                secondPurchase.purchasedAt(), "Corrected description",
                PurchaseStatus.VALID, secondPurchase.createdAt(), now.minusSeconds(10),
                null, secondPurchase.version() + 1);

        when(purchases.findById(tenantId, customerId, lastPurchase.id())).thenReturn(Optional.of(lastPurchase));
        when(purchases.findById(tenantId, customerId, secondPurchase.id())).thenReturn(Optional.of(correctedSecondPurchase));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
        verify(auditListener).onSecurityRejection(argThat(event ->
                event.reason() == ActionGateRejectionReason.STALE_STATE
                        && "AUTHORITATIVE_STATE_CHANGED".equals(event.diagnosticCode())
                        && Objects.equals(event.customerId(), customerId)
        ));
    }

    @Test
    void rejectsWhenNonLatestPurchaseInBaselineWasVoidedInFlight() {
        Purchase secondPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                lastPurchaseTime.minusSeconds(3600), "Purchase 2", now.minusSeconds(86400));
        PurchaseBaseline latestBaseline = PurchaseBaseline.from(lastPurchase);
        PurchaseBaseline secondBaseline = PurchaseBaseline.from(secondPurchase);

        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                dueEvaluation,
                assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)).context(),
                List.of(latestBaseline, secondBaseline));

        Purchase voidedSecondPurchase = Purchase.restore(
                secondPurchase.id(), tenantId, customerId,
                secondPurchase.purchasedAt(), secondPurchase.description(),
                PurchaseStatus.VOID, secondPurchase.createdAt(), now.minusSeconds(10),
                now.minusSeconds(10), secondPurchase.version() + 1);

        when(purchases.findById(tenantId, customerId, lastPurchase.id())).thenReturn(Optional.of(lastPurchase));
        when(purchases.findById(tenantId, customerId, secondPurchase.id())).thenReturn(Optional.of(voidedSecondPurchase));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
        verify(auditListener).onSecurityRejection(argThat(event ->
                event.reason() == ActionGateRejectionReason.STALE_STATE
                        && "AUTHORITATIVE_STATE_CHANGED".equals(event.diagnosticCode())
                        && Objects.equals(event.customerId(), customerId)
        ));
    }

    @Test
    void rejectsWhenNonLatestPurchaseInBaselineWasDeletedInFlight() {
        Purchase secondPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                lastPurchaseTime.minusSeconds(3600), "Purchase 2", now.minusSeconds(86400));
        PurchaseBaseline latestBaseline = PurchaseBaseline.from(lastPurchase);
        PurchaseBaseline secondBaseline = PurchaseBaseline.from(secondPurchase);

        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                dueEvaluation,
                assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)).context(),
                List.of(latestBaseline, secondBaseline));

        when(purchases.findById(tenantId, customerId, lastPurchase.id())).thenReturn(Optional.of(lastPurchase));
        when(purchases.findById(tenantId, customerId, secondPurchase.id())).thenReturn(Optional.empty());

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
        verify(auditListener).onSecurityRejection(argThat(event ->
                event.reason() == ActionGateRejectionReason.STALE_STATE
                        && "AUTHORITATIVE_STATE_CHANGED".equals(event.diagnosticCode())
                        && Objects.equals(event.customerId(), customerId)
        ));
    }

    @Test
    void rejectsWhenNonLatestPurchaseTimestampChangedInFlight() {
        Purchase secondPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                lastPurchaseTime.minusSeconds(3600), "Purchase 2", now.minusSeconds(86400));
        PurchaseBaseline latestBaseline = PurchaseBaseline.from(lastPurchase);
        PurchaseBaseline secondBaseline = PurchaseBaseline.from(secondPurchase);

        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                dueEvaluation,
                assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)).context(),
                List.of(latestBaseline, secondBaseline));

        Purchase modifiedSecondPurchase = Purchase.restore(
                secondPurchase.id(), tenantId, customerId,
                secondPurchase.purchasedAt().minusSeconds(10), secondPurchase.description(),
                PurchaseStatus.VALID, secondPurchase.createdAt(), now.minusSeconds(10),
                null, secondPurchase.version());

        when(purchases.findById(tenantId, customerId, lastPurchase.id())).thenReturn(Optional.of(lastPurchase));
        when(purchases.findById(tenantId, customerId, secondPurchase.id())).thenReturn(Optional.of(modifiedSecondPurchase));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isFalse();
        assertThat(decision.rejectionReason()).contains(ActionGateRejectionReason.STALE_STATE);
        verify(auditListener).onSecurityRejection(argThat(event ->
                event.reason() == ActionGateRejectionReason.STALE_STATE
                        && "AUTHORITATIVE_STATE_CHANGED".equals(event.diagnosticCode())
                        && Objects.equals(event.customerId(), customerId)
        ));
    }

    @Test
    void acceptsValidRecommendationWhenMultiplePurchasesInBaselineAreUnchanged() {
        Purchase secondPurchase = Purchase.create(new PurchaseId(UUID.randomUUID()), tenantId, customerId,
                lastPurchaseTime.minusSeconds(3600), "Purchase 2", now.minusSeconds(86400));
        PurchaseBaseline latestBaseline = PurchaseBaseline.from(lastPurchase);
        PurchaseBaseline secondBaseline = PurchaseBaseline.from(secondPurchase);

        RecommendationContextAssembler.Assembly assembly = new RecommendationContextAssembler.Assembly(
                dueEvaluation,
                assembly(dueEvaluation, List.of(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)).context(),
                List.of(latestBaseline, secondBaseline));

        when(purchases.findById(tenantId, customerId, lastPurchase.id())).thenReturn(Optional.of(lastPurchase));
        when(purchases.findById(tenantId, customerId, secondPurchase.id())).thenReturn(Optional.of(secondPurchase));

        ActionGateDecision decision = gate.evaluate(customerId, assembly, sampleAction);

        assertThat(decision.isAccepted()).isTrue();
        assertThat(decision.rejectionReason()).isEmpty();
        verify(auditListener, never()).onSecurityRejection(any());
    }

    private void verifyRejection(CustomerId customerId, ActionGateRejectionReason reason) {
        verify(auditListener).onSecurityRejection(argThat(event ->
                Objects.equals(event.customerId(), customerId) && event.reason() == reason));
    }

    private RecommendationContextAssembler.Assembly assembly(FollowUpEvaluation evaluation, List<SemanticAction> allowedActions) {
        return assembly(evaluation, allowedActions, evaluation.lastPurchaseAt() != null && lastPurchase != null ? lastPurchase.id() : null);
    }

    private RecommendationContextAssembler.Assembly assembly(FollowUpEvaluation evaluation, List<SemanticAction> allowedActions, PurchaseId purchaseId) {
        List<PurchaseBaseline> baselines = purchaseId == null ? List.of() :
                (evaluation.lastPurchaseAt() != null ? List.of(new PurchaseBaseline(purchaseId, 0L, evaluation.lastPurchaseAt())) : List.of());
        if (evaluation.nextFollowUpDate() == null
                || (evaluation.status() != FollowUpStatus.DUE && evaluation.status() != FollowUpStatus.OVERDUE)) {
            return new RecommendationContextAssembler.Assembly(evaluation, null, baselines);
        }
        List<TrustedFollowUpReason> trustedReasons = new java.util.ArrayList<>();
        for (FollowUpReason r : evaluation.reasons()) {
            try {
                trustedReasons.add(TrustedFollowUpReason.valueOf(r.name()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        List<Instant> purchaseTimes = evaluation.lastPurchaseAt() != null ? List.of(evaluation.lastPurchaseAt()) : List.of();
        RecommendationContext.TrustedFacts trusted = new RecommendationContext.TrustedFacts(
                evaluation.tenantDate(), evaluation.status().name(),
                trustedReasons,
                evaluation.effectiveCadenceDays(), evaluation.nextFollowUpDate(), true,
                purchaseTimes, allowedActions);
        RecommendationContext.UntrustedText untrusted = new RecommendationContext.UntrustedText(
                "Test Customer", "Some notes", List.of("Purchase description"));
        return new RecommendationContextAssembler.Assembly(evaluation, new RecommendationContext(trusted, untrusted), baselines);
    }
}
