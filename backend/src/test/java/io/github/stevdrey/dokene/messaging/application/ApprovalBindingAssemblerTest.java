// ABOUTME: Unit proof of the approval binding (ADR 0024 §2, issue 136): the closed parameter vocabulary, fail-closed
// ABOUTME: template, language, value and consent refusals, and which changes move the fingerprint.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessageApproval;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver.ResolvedTemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ApprovalBindingAssemblerTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final String BODY = "Hola, ¿cómo le ha ido con su compra?";

    private final TemplateMappingResolver templates = mock(TemplateMappingResolver.class);
    private final ContactPolicyRepository contactPolicies = mock(ContactPolicyRepository.class);
    private final ApprovalBindingAssembler assembler = new ApprovalBindingAssembler(templates, contactPolicies);
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final SenderRevision sender = new SenderRevision(UUID.randomUUID(), "100000000000001",
            "200000000000001", 1);
    private Customer customer;
    private CustomerPhone phone;

    @BeforeEach
    void setUp() {
        phone = CustomerPhone.create("+50688880001", true);
        customer = Customer.create(new CustomerId(UUID.randomUUID()), tenantId, "Ana Pérez", null, List.of(phone),
                NOW);
        mapping("es", List.of("customer_name", "message_body"), 3);
        when(contactPolicies.whatsAppConsentRevision(any(), eq(phone.id()))).thenReturn(OptionalLong.of(7));
    }

    @Test
    void rendersTheClosedVocabularyInTheMappingsParameterOrder() {
        mapping("es_MX", List.of("message_body", "customer_name"), 3);

        ApprovalFingerprint.Binding binding = assembler.bind(message(BODY), customer, sender);

        assertThat(binding.parameterValues()).containsExactly(BODY, "Ana Pérez");
        assertThat(binding.template().version()).isEqualTo(3);
        assertThat(binding.template().language()).isEqualTo("es_MX");
        assertThat(binding.consentRevision()).isEqualTo(7);
        assertThat(binding.sender()).isEqualTo(sender);
        assertThat(binding.recipientPhone()).isEqualTo("+50688880001");
    }

    @Test
    void bindsTheVerifiedParameterFormatAndAFormatChangeMovesTheFingerprint() {
        ApprovalFingerprint.Binding positional = assembler.bind(message(BODY), customer, sender);
        mapping("es", TemplateParameterFormat.NAMED, List.of("customer_name", "message_body"), 3);
        ApprovalFingerprint.Binding named = assembler.bind(message(BODY), customer, sender);

        assertThat(positional.template().parameterFormat()).isEqualTo(ApprovalFingerprint.ParameterFormat.POSITIONAL);
        assertThat(named.template().parameterFormat()).isEqualTo(ApprovalFingerprint.ParameterFormat.NAMED);
        assertThat(named.template().parameterNames()).containsExactly("customer_name", "message_body");
        assertThat(ApprovalFingerprint.of(positional)).isNotEqualTo(ApprovalFingerprint.of(named));
    }

    @Test
    void refusesWhenTheIntentHasNoSendableMapping() {
        when(templates.resolve(SemanticTemplateIntent.GENERAL_FOLLOW_UP)).thenReturn(new TemplateMappingResolver.NotMapped());
        assertRefused(() -> assembler.bind(message(BODY), customer, sender), MessagingErrorCode.TEMPLATE_NOT_MAPPED);

        when(templates.resolve(SemanticTemplateIntent.GENERAL_FOLLOW_UP)).thenReturn(new TemplateMappingResolver.Disabled());
        assertRefused(() -> assembler.bind(message(BODY), customer, sender), MessagingErrorCode.TEMPLATE_NOT_MAPPED);
    }

    @Test
    void refusesAParameterOutsideTheVocabularyOrATemplateInAnotherLanguage() {
        mapping("es", List.of("customer_name", "discount_code"), 3);
        assertRefused(() -> assembler.bind(message(BODY), customer, sender), MessagingErrorCode.TEMPLATE_NOT_MAPPED);

        mapping("en_US", List.of("message_body"), 3);
        assertRefused(() -> assembler.bind(message(BODY), customer, sender), MessagingErrorCode.TEMPLATE_NOT_MAPPED);
    }

    @Test
    void refusesARenderedValueTheProviderWouldReject() {
        // Meta body parameters admit no line breaks and no run of more than four spaces.
        assertRefused(() -> assembler.bind(message("Hola,\nseguimos atentos."), customer, sender),
                MessagingErrorCode.BODY_REJECTED);
        assertRefused(() -> assembler.bind(message("Hola,     seguimos atentos."), customer, sender),
                MessagingErrorCode.BODY_REJECTED);
    }

    @Test
    void refusesWhenTheContactsConsentIsNotCurrentlyGranted() {
        when(contactPolicies.whatsAppConsentRevision(any(), eq(phone.id()))).thenReturn(OptionalLong.empty());
        assertRefused(() -> assembler.bind(message(BODY), customer, sender), MessagingErrorCode.NO_CONTACT_CONSENT);
    }

    @Test
    void anEditOfWhatWasApprovedMovesTheFingerprintWhileTheSameStateKeepsIt() {
        OutboundMessage message = message(BODY);
        ApprovalFingerprint approved = ApprovalFingerprint.of(assembler.bind(message, customer, sender));
        assertThat(approved.covers(assembler.bind(message, customer, sender))).isTrue();

        mapping("es", List.of("customer_name", "message_body"), 4);
        assertThat(approved.covers(assembler.bind(message, customer, sender))).as("template edited").isFalse();
        mapping("es", List.of("customer_name", "message_body"), 3);

        when(contactPolicies.whatsAppConsentRevision(any(), eq(phone.id()))).thenReturn(OptionalLong.of(9));
        assertThat(approved.covers(assembler.bind(message, customer, sender))).as("consent re-granted").isFalse();
        when(contactPolicies.whatsAppConsentRevision(any(), eq(phone.id()))).thenReturn(OptionalLong.of(7));

        customer.update("Ana María Pérez", null, List.of(phone), NOW.plusSeconds(1));
        assertThat(approved.covers(assembler.bind(message, customer, sender))).as("customer renamed").isFalse();
        customer.update("Ana Pérez", null, List.of(phone), NOW.plusSeconds(2));

        SenderRevision rotated = new SenderRevision(sender.integrationId(), sender.phoneNumberId(),
                sender.businessAccountId(), 2);
        assertThat(approved.covers(assembler.bind(message, customer, rotated))).as("credential rotated").isFalse();
        assertThat(approved.covers(assembler.bind(message, customer, sender))).isTrue();
    }

    @Test
    void submitAppliesTheSameRenderChecksSoAnUnapprovableMessageIsNeverStored() {
        assembler.requireRenderable(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", BODY, customer);

        assertRefused(() -> assembler.requireRenderable(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419",
                "Hola,\nseguimos atentos.", customer), MessagingErrorCode.BODY_REJECTED);
        mapping("es", List.of("message_body", "discount_code"), 3);
        assertRefused(() -> assembler.requireRenderable(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", BODY,
                customer), MessagingErrorCode.TEMPLATE_NOT_MAPPED);
        mapping("pt_BR", List.of("message_body"), 3);
        assertRefused(() -> assembler.requireRenderable(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", BODY,
                customer), MessagingErrorCode.TEMPLATE_NOT_MAPPED);
    }

    @Test
    void approvalRecordsNeverPrintTheirFingerprintOrNote() {
        ApprovalFingerprint fingerprint = ApprovalFingerprint.of(assembler.bind(message(BODY), customer, sender));
        MessageApproval approval = MessageApproval.approve(UUID.randomUUID(), tenantId.value(), UUID.randomUUID(),
                "nota privada", fingerprint, NOW, UUID.randomUUID(), UUID.randomUUID());

        assertThat(fingerprint.toString()).doesNotContain(fingerprint.value());
        assertThat(approval.toString()).doesNotContain(fingerprint.value()).doesNotContain("nota privada");
    }

    @Test
    void refusesACustomerThatDoesNotOwnTheMessage() {
        Customer other = Customer.create(new CustomerId(UUID.randomUUID()), tenantId, "Otro", null,
                List.of(CustomerPhone.create("+50688880002", true)), NOW);
        assertThatThrownBy(() -> assembler.bind(message(BODY), other, sender))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void mapping(String language, List<String> parameterNames, long version) {
        mapping(language, TemplateParameterFormat.POSITIONAL, parameterNames, version);
    }

    private void mapping(String language, TemplateParameterFormat format, List<String> parameterNames, long version) {
        when(templates.resolve(SemanticTemplateIntent.GENERAL_FOLLOW_UP)).thenReturn(new TemplateMappingResolver.Resolved(
                new ResolvedTemplateMapping(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "general_follow_up", language,
                        format, parameterNames, version)));
    }

    private OutboundMessage message(String body) {
        return OutboundMessage.submit(new OutboundMessage.Submission(UUID.randomUUID(), tenantId.value(),
                customer.id().value(), phone.id(), phone.e164(), SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", MessageOrigin.MANUAL, body, 4,
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), NOW).next();
    }

    private static void assertRefused(Executable call, MessagingErrorCode code) {
        assertThatThrownBy(call::execute).isInstanceOfSatisfying(MessagingRefusedException.class,
                refusal -> assertThat(refusal.code()).isEqualTo(code));
    }
}
