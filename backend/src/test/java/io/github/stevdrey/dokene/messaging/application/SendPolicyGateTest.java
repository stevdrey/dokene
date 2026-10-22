// ABOUTME: Unit tests for the last-moment send gate (issue 138): ADR order, fail closed, abuse bounds, and that the
// ABOUTME: command it permits carries exactly the approved binding's values and sender.
package io.github.stevdrey.dokene.messaging.application;

import static io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.ParameterFormat.NAMED;
import static io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.ParameterFormat.POSITIONAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.FailureCategory;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageChannel;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRateLimitedException;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver.ResolvedTemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateContent;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SendPolicyGateTest {
    private static final TemplateContent CONTENT = new TemplateContent(null, "Synthetic body", null);
    private static final String BODY = "Hola, queríamos saber cómo le ha ido con su compra.";
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private final OutboundMessageRepository messages = mock(OutboundMessageRepository.class);
    private final MessageSendGuards guards = mock(MessageSendGuards.class);
    private final ApprovalBindingAssembler bindings = mock(ApprovalBindingAssembler.class);
    private final OutboundSafetyControl safety = mock(OutboundSafetyControl.class);

    private final TenantId tenantId = TenantId.random();
    private final TenantContext context = new TenantContext(tenantId, new IdentityId(UUID.randomUUID()),
            TenantMembershipId.random(), TenantRole.OPERATOR);
    private final Customer customer = Customer.create(new CustomerId(UUID.randomUUID()), tenantId, "Ana", null,
            List.of(CustomerPhone.create("+50688887777", true)), NOW.minusSeconds(3600));
    private final SenderRevision sender = new SenderRevision(UUID.randomUUID(), "100000000000001",
            "200000000000001", 1);
    private final OutboundMessage approved = approve(submitted());
    private final ApprovalFingerprint.Binding binding = binding(approved);

    @Test
    void aKillSwitchRefusesBeforeAnyOtherStateIsRead() {
        when(safety.evaluate(tenantId)).thenReturn(
                new OutboundSafetyControl.Blocked(MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY));

        assertRefused(gate(defaults()), approved, MessagingErrorCode.OUTBOUND_DISABLED_GLOBALLY);
        verifyNoInteractions(messages, guards, bindings);
    }

    @Test
    void anApprovalWithoutAFingerprintNeverSends() {
        permitted();
        when(messages.findApprovalByMessage(any(), any())).thenReturn(Optional.empty());

        assertRefused(gate(defaults()), approved, MessagingErrorCode.APPROVAL_INVALIDATED);
        verifyNoInteractions(bindings);
    }

    @Test
    void aRecentSendToTheSameCustomerExceedsTheContactFrequency() {
        permitted();
        approvedWith(ApprovalFingerprint.of(binding));
        when(messages.sentToCustomerSince(eq(approved.tenantId()), eq(approved.customerId()), any()))
                .thenReturn(true);

        assertRefused(gate(defaults()), approved, MessagingErrorCode.CONTACT_FREQUENCY_EXCEEDED);
        verify(messages).sentToCustomerSince(approved.tenantId(), approved.customerId(),
                NOW.minus(Duration.ofHours(24)));
        verifyNoInteractions(bindings);
    }

    /** covers: AC-6 of spec 0013 (a zero interval stays allowed: no frequency bound, the send still binds). */
    @Test
    void aZeroContactIntervalSkipsTheFrequencyBoundAndStillBinds() {
        permitted();
        approvedWith(ApprovalFingerprint.of(binding));
        when(bindings.bind(approved, customer, sender)).thenReturn(binding);

        OutboundSendCommand command = gate(new MessagingDispatchProperties(null, Duration.ZERO, null, null))
                .evaluate(context, customer, approved, NOW);

        assertThat(command.parameters()).containsExactly("Ana", BODY);
        verify(messages, never()).sentToCustomerSince(any(), any(), any());
        verify(bindings).bind(approved, customer, sender);
    }

    @Test
    void theTenantRateRefusalCarriesTheRemainingWindow() {
        UUID tenant = approved.tenantId();
        when(messages.countAttemptsStartedSince(tenant, NOW.minus(Duration.ofMinutes(1)))).thenReturn(30);
        when(messages.oldestAttemptStartedSince(tenant, NOW.minus(Duration.ofMinutes(1))))
                .thenReturn(Optional.of(NOW.minusSeconds(45)));

        // The oldest counted attempt leaves the one-minute window in 15 seconds.
        assertThatThrownBy(() -> gate(defaults()).requireRateBudget(tenant, NOW))
                .isInstanceOfSatisfying(MessagingRateLimitedException.class, throttled -> {
                    assertThat(throttled.code()).isEqualTo(MessagingErrorCode.RATE_LIMITED);
                    assertThat(throttled.retryAfter()).isEqualTo(Duration.ofSeconds(15));
                });
        // The budget lock is the caller's, taken before it reads the clock it passes in.
        verify(messages, never()).acquireSendBudgetLock(any());
    }

    @Test
    void aTenantBelowTheRateIsAdmitted() {
        when(messages.countAttemptsStartedSince(approved.tenantId(), NOW.minus(Duration.ofMinutes(1))))
                .thenReturn(29);

        gate(defaults()).requireRateBudget(approved.tenantId(), NOW);
    }

    @Test
    void aBindingTheApprovalDoesNotCoverIsInvalidated() {
        permitted();
        approvedWith(ApprovalFingerprint.of(binding));
        ApprovalFingerprint.Binding edited = new ApprovalFingerprint.Binding(binding.tenantId(), binding.messageId(),
                binding.customerId(), binding.contactId(), binding.recipientPhone(), binding.channel(),
                binding.action(), binding.templateIntent(), binding.locale(), binding.body(),
                binding.sourcePolicyVersion(), binding.consentRevision(),
                new ApprovalFingerprint.TemplateRevision(2, "general_follow_up", "es", POSITIONAL,
                        List.of("customer_name", "message_body"), null, "Synthetic body", null, List.of()),
                binding.parameterValues(), sender);
        when(bindings.bind(approved, customer, sender)).thenReturn(edited);

        assertRefused(gate(defaults()), approved, MessagingErrorCode.APPROVAL_INVALIDATED);
    }

    @Test
    void anApprovalTakenUnderPositionalIsInvalidatedOnceTheTemplateResolvesAsNamed() {
        TemplateMappingResolver resolver = mock(TemplateMappingResolver.class);
        ContactPolicyRepository contactPolicies = mock(ContactPolicyRepository.class);
        when(contactPolicies.whatsAppConsentRevision(customer, approved.contactId())).thenReturn(OptionalLong.of(1));
        ApprovalBindingAssembler real = new ApprovalBindingAssembler(resolver, contactPolicies);
        resolvesAs(resolver, TemplateParameterFormat.POSITIONAL);
        ApprovalFingerprint atApproval = ApprovalFingerprint.of(real.bind(approved, customer, sender));
        permitted();
        approvedWith(atApproval);
        // The template was re-verified with named placeholders after the approval.
        resolvesAs(resolver, TemplateParameterFormat.NAMED);

        assertRefused(new SendPolicyGate(messages, guards, real, safety, defaults()), approved,
                MessagingErrorCode.APPROVAL_INVALIDATED);
    }

    @Test
    void aNamedTemplateSendCarriesTheApprovedFormatAndNames() {
        ApprovalFingerprint.Binding named = new ApprovalFingerprint.Binding(binding.tenantId(), binding.messageId(),
                binding.customerId(), binding.contactId(), binding.recipientPhone(), binding.channel(),
                binding.action(), binding.templateIntent(), binding.locale(), binding.body(),
                binding.sourcePolicyVersion(), binding.consentRevision(),
                new ApprovalFingerprint.TemplateRevision(1, "general_follow_up", "es", NAMED,
                        List.of("customer_name", "message_body"), null, "Synthetic body", null, List.of()),
                binding.parameterValues(), sender);
        permitted();
        approvedWith(ApprovalFingerprint.of(named));
        when(bindings.bind(approved, customer, sender)).thenReturn(named);

        OutboundSendCommand command = gate(defaults()).evaluate(context, customer, approved, NOW);

        assertThat(command.parameterFormat()).isEqualTo(NAMED);
        assertThat(command.parameterNames()).containsExactly("customer_name", "message_body");
        assertThat(command.parameters()).containsExactly("Ana", BODY);
    }

    @Test
    void theAttemptCapIsEnforcedBeforeAnotherAttempt() {
        OutboundMessage retried = approved.requestSend(UUID.randomUUID(), NOW).next().startAttempt(NOW).next()
                .completeAttempt(new ProviderSendResult.FailedTransiently(FailureCategory.RATE_LIMITED), NOW, 3)
                .next();
        permitted();
        ApprovalFingerprint.Binding retriedBinding = binding(retried);
        approvedWith(ApprovalFingerprint.of(retriedBinding));
        when(bindings.bind(retried, customer, sender)).thenReturn(retriedBinding);

        assertRefused(gate(new MessagingDispatchProperties(1, null, null, null)), retried,
                MessagingErrorCode.SEND_ATTEMPTS_EXHAUSTED);
    }

    @Test
    void aPermittedSendCarriesExactlyTheApprovedValuesKeyAndSender() {
        permitted();
        approvedWith(ApprovalFingerprint.of(binding));
        when(bindings.bind(approved, customer, sender)).thenReturn(binding);

        OutboundSendCommand command = gate(defaults()).evaluate(context, customer, approved, NOW);

        assertThat(command.parameters()).containsExactly("Ana", BODY);
        assertThat(command.parameterFormat()).isEqualTo(POSITIONAL);
        assertThat(command.parameterNames()).containsExactly("customer_name", "message_body");
        assertThat(command.providerTemplateName()).isEqualTo("general_follow_up");
        assertThat(command.language()).isEqualTo("es");
        assertThat(command.sendKey()).isEqualTo(approved.sendKey());
        assertThat(command.expectedSender()).isEqualTo(sender);
        assertThat(command.recipientPhone()).isEqualTo(approved.recipientPhone());
        verify(guards).check(customer, approved.contactId(), approved.action(), approved.templateIntent(),
                approved.body(), approved.locale(), Optional.empty());
        // The tenant rate is the caller's last step, under its lock; the policy evaluation never counts it.
        verify(messages, never()).countAttemptsStartedSince(any(), any());
    }

    @Test
    void aContactTheCustomerNoLongerOwnsIsRefusedBeforeTheGuards() {
        permitted();
        approvedWith(ApprovalFingerprint.of(binding));
        Customer renumbered = Customer.create(customer.id(), tenantId, "Ana", null,
                List.of(CustomerPhone.create("+50688886666", true)), NOW.minusSeconds(3600));

        assertThatThrownBy(() -> gate(defaults()).evaluate(context, renumbered, approved, NOW))
                .isInstanceOf(MessagingRefusedException.class)
                .extracting(exception -> ((MessagingRefusedException) exception).code())
                .isEqualTo(MessagingErrorCode.CONTACT_NOT_OWNED);
        verify(guards, never()).check(any(), any(), any(), any(), any(), any(), any());
    }

    private SendPolicyGate gate(MessagingDispatchProperties properties) {
        return new SendPolicyGate(messages, guards, bindings, safety, properties);
    }

    private static MessagingDispatchProperties defaults() {
        return new MessagingDispatchProperties(null, null, null, null);
    }

    private static void resolvesAs(TemplateMappingResolver resolver, TemplateParameterFormat format) {
        when(resolver.resolve(SemanticTemplateIntent.GENERAL_FOLLOW_UP)).thenReturn(new TemplateMappingResolver.Resolved(
                new ResolvedTemplateMapping(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "general_follow_up", "es",
                        format, List.of("customer_name", "message_body"), CONTENT, 1)));
    }

    private void permitted() {
        when(safety.evaluate(tenantId)).thenReturn(new OutboundSafetyControl.Permitted(sender));
    }

    private void approvedWith(ApprovalFingerprint fingerprint) {
        when(messages.findApprovalByMessage(approved.tenantId(), approved.id())).thenReturn(Optional.of(
                MessageApproval.approve(UUID.randomUUID(), approved.tenantId(), approved.id(), null, fingerprint,
                        NOW, UUID.randomUUID(), UUID.randomUUID())));
    }

    private void assertRefused(SendPolicyGate gate, OutboundMessage message, MessagingErrorCode code) {
        assertThatThrownBy(() -> gate.evaluate(context, customer, message, NOW))
                .isInstanceOf(MessagingRefusedException.class)
                .extracting(exception -> ((MessagingRefusedException) exception).code()).isEqualTo(code);
    }

    private OutboundMessage submitted() {
        return OutboundMessage.submit(new OutboundMessage.Submission(UUID.randomUUID(), tenantId.value(),
                customer.id().value(), customer.phones().getFirst().id(), "+50688887777",
                SemanticAction.GENERAL_CHECK_IN, SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419",
                MessageOrigin.MANUAL, BODY, 0, UUID.randomUUID(), context.membershipId().value(),
                context.identityId().value()), NOW.minusSeconds(60)).next();
    }

    private static OutboundMessage approve(OutboundMessage message) {
        return message.approve(UUID.randomUUID(), NOW.minusSeconds(30)).next();
    }

    private ApprovalFingerprint.Binding binding(OutboundMessage message) {
        return new ApprovalFingerprint.Binding(message.tenantId(), message.id(), message.customerId(),
                message.contactId(), message.recipientPhone(), MessageChannel.WHATSAPP, message.action(),
                message.templateIntent(), message.locale(), message.body(), message.sourcePolicyVersion(), 1,
                new ApprovalFingerprint.TemplateRevision(1, "general_follow_up", "es", POSITIONAL,
                        List.of("customer_name", "message_body"), null, "Synthetic body", null, List.of()),
                List.of("Ana", BODY), sender);
    }
}
