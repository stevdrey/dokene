// ABOUTME: Unit proof of issue 326: template parameter values and the rendered body pass the message safety rules at
// ABOUTME: submit, review and approval, with the body's grounding and vocabulary and a closed refusal that quotes nothing.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.stevdrey.dokene.ai.domain.DraftGroundingContext;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.ContactPolicyRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.customer.domain.CustomerPhone;
import io.github.stevdrey.dokene.followup.application.DraftGroundingAssembler;
import io.github.stevdrey.dokene.followup.application.FollowUpService;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessageOrigin;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingRefusedException;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver.ResolvedTemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateContent;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RenderedContentSafetyTest {
    private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    private static final String BODY = "Queríamos saber cómo le ha ido con su compra.";
    private static final TemplateContent GREETING = new TemplateContent(null, "Hola {{1}}: {{2}}", null);

    private final TemplateMappingResolver templates = mock(TemplateMappingResolver.class);
    private final ContactPolicyRepository contactPolicies = mock(ContactPolicyRepository.class);
    private final DraftGroundingAssembler grounding = mock(DraftGroundingAssembler.class);
    private final ApprovalBindingAssembler assembler = new ApprovalBindingAssembler(templates, contactPolicies,
            new MessageSendGuards(contactPolicies, mock(FollowUpService.class), grounding));
    private final TenantId tenantId = new TenantId(UUID.randomUUID());
    private final SenderRevision sender = new SenderRevision(UUID.randomUUID(), "100000000000001",
            "200000000000001", 1);
    private CustomerPhone phone;

    @BeforeEach
    void setUp() {
        phone = CustomerPhone.create("+50688880001", true);
        mapping("es", GREETING);
        purchases("Televisor de sala");
        when(contactPolicies.whatsAppConsentRevision(any(), eq(phone.id()))).thenReturn(OptionalLong.of(7));
    }

    @Test
    void aSafeRenderedMessageIsReviewedAndBoundUnchanged() {
        Customer customer = customer("Ana M. Pérez");

        assembler.requireRenderable(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "es-419", BODY, customer);

        assertThat(assembler.review(message("es-419", BODY, customer), customer).orElseThrow().renderedBody())
                .isEqualTo("Hola Ana M. Pérez: " + BODY);
        assertThat(assembler.bind(message("es-419", BODY, customer), customer, sender).parameterValues())
                .containsExactly("Ana M. Pérez", BODY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ana promo.example", "https://promo.example/a", "Ana hxxp://promo[.]example", "Ana meta_promo",
            "Ana 50%", "Ana $100", "Ana 100 colones", "Ana descuento", "Ana gr4tis", "Ana ​cupón"})
    void anUnsafeParameterValueIsRefusedOnEveryRenderingPath(String name) {
        Customer customer = customer(name);
        OutboundMessage message = message("es-419", BODY, customer);

        assertRefusedQuotingNothing(() -> assembler.requireRenderable(SemanticTemplateIntent.GENERAL_FOLLOW_UP,
                "es-419", BODY, customer), name);
        assertRefusedQuotingNothing(() -> assembler.bind(message, customer, sender), name);
        assertThat(assembler.review(message, customer)).isEmpty();
    }

    /** Neither value is a link alone: only the rendered body, the text the recipient reads, shows one. */
    @Test
    void valuesThatFormUnsafeContentOnlyOnceRenderedAreRefused() {
        mapping("es", new TemplateContent(null, "{{1}}.{{2}}", null));
        Customer customer = customer("promo");

        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", "example", customer), customer, sender),
                "promo");
    }

    @Test
    void anOfferIsGroundedOnlyByAPurchaseAsInTheBodyNeverByTheNameItself() {
        String offer = "Su compra tuvo 10% de descuento.";
        Customer customer = customer("Ana Pérez");
        purchases("Televisor con 10% de descuento");
        assertThat(assembler.bind(message("es-419", offer, customer), customer, sender).parameterValues())
                .containsExactly("Ana Pérez", offer);

        purchases("Televisor de sala");
        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", offer, customer), customer, sender), offer);

        Customer named = customer("Ana 10% de descuento");
        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", BODY, named), named, sender), "descuento");
    }

    @Test
    void theRenderedContentUsesTheVocabularyOfTheMessageLocale() {
        mapping("en_US", new TemplateContent(null, "Hello {{1}}: {{2}}", null));
        String english = "We hope you are enjoying it.";
        Customer sale = customer("Sale Mora");
        assertRefusedQuotingNothing(() -> assembler.bind(message("en-US", english, sale), sale, sender), "Sale");

        mapping("es", GREETING);
        assertThat(assembler.bind(message("es-419", BODY, sale), sale, sender).parameterValues())
                .containsExactly("Sale Mora", BODY);
    }

    private void assertRefusedQuotingNothing(Executable operation, String value) {
        assertThatThrownBy(operation::execute).isInstanceOfSatisfying(MessagingRefusedException.class, refused -> {
            assertThat(refused.code()).isEqualTo(MessagingErrorCode.BODY_REJECTED);
            assertThat(refused.getMessage()).doesNotContain(value);
        });
    }

    private void purchases(String description) {
        doAnswer(invocation -> {
            Customer customer = invocation.getArgument(0);
            return new DraftGroundingContext(customer.displayName(), customer.notes(), List.of(description), List.of(),
                    null, null);
        }).when(grounding).renderedContentGrounding(any());
    }

    private void mapping(String language, TemplateContent content) {
        when(templates.resolve(SemanticTemplateIntent.GENERAL_FOLLOW_UP)).thenReturn(new TemplateMappingResolver.Resolved(
                new ResolvedTemplateMapping(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "general_follow_up", language,
                        TemplateParameterFormat.POSITIONAL, List.of("customer_name", "message_body"), content, 3)));
    }

    private Customer customer(String name) {
        return Customer.create(new CustomerId(UUID.randomUUID()), tenantId, name, null, List.of(phone), NOW);
    }

    private OutboundMessage message(String locale, String body, Customer customer) {
        return OutboundMessage.submit(new OutboundMessage.Submission(UUID.randomUUID(), tenantId.value(),
                customer.id().value(), phone.id(), phone.e164(), SemanticAction.GENERAL_CHECK_IN,
                SemanticTemplateIntent.GENERAL_FOLLOW_UP, locale, MessageOrigin.MANUAL, body, 1, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID()), NOW).next();
    }
}
