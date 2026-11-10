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
import io.github.stevdrey.dokene.ai.domain.DraftSafetyValidator;
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
import java.util.Set;
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

    @ParameterizedTest
    @ValueSource(strings = {"Ana Bono", "Luis Soles", "Eva Reales", "Mario Precio", "Ana M. Bono", "Ana María Bono"})
    void aListedSurnameAfterAGivenNameIsNotAnOfferWord(String name) {
        Customer customer = customer(name);

        assertThat(assembler.review(message("es-419", BODY, customer), customer).orElseThrow().renderedBody())
                .isEqualTo("Hola " + name + ": " + BODY);
        assertThat(assembler.bind(message("es-419", BODY, customer), customer, sender).parameterValues())
                .containsExactly(name, BODY);
    }

    /** The exception is not a way in: an offer word first, alone, or with a number, a symbol or another offer word. */
    @ParameterizedTest
    @ValueSource(strings = {"Bono", "Precio especial", "Ana Precio 100", "Ana Soles 500", "Ana Bono gratis",
            "Ana Precio $", "Ana Precio promo.example", "Ana Regalo", "Ana bono", "Aumento de Precio", "Cambio De Precio",
            "Su Nuevo Precio", "Le Damos Un Bono", "Cien Soles", "Mil Reales", "Bono Precio", "Ana Bono Mora"})
    void aListedSurnameOpensNoOtherContent(String name) {
        Customer customer = customer(name);

        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", BODY, customer), customer, sender), name);
    }

    @Test
    void onlyTheCustomersOwnFullNameIsExempted() {
        Customer customer = customer("Ana Precio");

        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", "Tenemos un precio nuevo.", customer),
                customer, sender), "precio");
        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", "Ana Precio es de 100.", customer),
                customer, sender), "precio");
    }

    /** Every written quantity the validator reads, exhaustively, so the exemption cannot drift from its rules. */
    @Test
    void noWrittenQuantityTheValidatorReadsCanPrecedeASurnameOrStandNearTheName() {
        for (String word : DraftSafetyValidator.writtenQuantityWords()) {
            Customer quantity = customer(Character.toUpperCase(word.charAt(0)) + word.substring(1) + " Soles");
            assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", BODY, quantity), quantity, sender),
                    "Soles");
            if (!Set.of("un", "una").contains(word)) {
                Customer surname = customer("Ana Soles");
                assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", "Ana Soles " + word + ".", surname),
                        surname, sender), "Soles");
            }
        }
    }

    /** A disguised spelling of an earlier word is read as the validator reads it, or the name does not qualify. */
    @ParameterizedTest
    @ValueSource(strings = {"Cién Soles", "Cien- Soles", "Nuevo' Precio", "Cien-Mil Soles", "\uFF23ien Soles",
            "\u0421ien Soles", "Mi\u0301l Soles"})
    void aDisguisedEarlierWordDoesNotQualifyTheName(String name) {
        Customer customer = customer(name);

        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", BODY, customer), customer, sender), "Soles");
    }

    /** A quantity in any form the validator reads, or a commercial word, keeps the name subject to the rules. */
    @ParameterizedTest
    @ValueSource(strings = {"Ana Precio \uFF15\uFF10\uFF10.", "Ana Precio cien.", "Son cien Ana Precio.",
            "Ana Precio especial para usted.", "Ana Precio \u00B2."})
    void aQuantityOrCommercialWordNearTheNameKeepsItRefused(String body) {
        Customer customer = customer("Ana Precio");

        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", body, customer), customer, sender), "Precio");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Best Price", "Flash Sale", "Your New Price", "Ten Rand", "Sale Mora"})
    void anEnglishNameThatReadsAsAnOfferIsRefusedForAnEnglishMessage(String name) {
        mapping("en_US", new TemplateContent(null, "Hello {{1}}: {{2}}", null));
        Customer customer = customer(name);

        assertRefusedQuotingNothing(() -> assembler.bind(message("en-US", "We hope you are enjoying it.", customer),
                customer, sender), name);
    }

    /** Reach is measured on the text as the validator reads it, so padding it ignores cannot move a number away. */
    @ParameterizedTest
    @ValueSource(strings = {"\u200D", "\u00A0", "\u202F"})
    void paddingTheValidatorIgnoresDoesNotMoveANumberOutOfReach(String padding) {
        Customer customer = customer("Ana Precio");
        String after = "Ana Precio" + padding.repeat(40) + "100.";
        String before = "100" + padding.repeat(40) + "Ana Precio.";

        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", after, customer), customer, sender), "100");
        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", before, customer), customer, sender), "100");
    }

    @Test
    void anEnglishSurnameIsListedForAnEnglishMessage() {
        mapping("en_US", new TemplateContent(null, "Hello {{1}}: {{2}}", null));
        Customer customer = customer("Ana Price");

        assertThat(assembler.bind(message("en-US", "We hope you are enjoying it.", customer), customer, sender)
                .parameterValues()).containsExactly("Ana Price", "We hope you are enjoying it.");
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

    /** An identity value carries no offer: a purchase that would ground the same text in a body does not ground it. */
    @Test
    void aNameCarryingAnOfferIsRefusedEvenWhenAPurchaseWouldGroundIt() {
        purchases("Televisor con 10% de descuento");
        Customer customer = customer("Ana 10% de descuento");

        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", BODY, customer), customer, sender),
                "descuento");
    }

    @Test
    void anOrdinaryWordNearAListedSurnameDoesNotRefuseIt() {
        Customer customer = customer("Ana Bono");
        for (String body : List.of("Hoy queremos saber cómo está.", "Tenemos un nuevo horario.")) {
            assertThat(assembler.bind(message("es-419", body, customer), customer, sender).parameterValues())
                    .containsExactly("Ana Bono", body);
        }
        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", "Es especial.", customer), customer, sender),
                "Bono");
        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", "Son cien.", customer), customer, sender),
                "Bono");
        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", "Son 100.", customer), customer, sender),
                "Bono");
    }

    /** Links are read in the exact rendered text: a capital after the period does not make it a sentence break. */
    @ParameterizedTest
    @ValueSource(strings = {"Hola {{1}}.Com {{2}}", "Hola {{1}}.COM {{2}}", "Hola {{1}}.Gracias {{2}}"})
    void aDomainFormedAcrossAValueAndFixedTextIsRefusedWhateverItsCase(String fixedBody) {
        mapping("es", new TemplateContent(null, fixedBody, null));
        Customer customer = customer("promo");
        OutboundMessage message = message("es-419", BODY, customer);

        assertRefusedQuotingNothing(() -> assembler.bind(message, customer, sender), "promo");
        assertThat(assembler.review(message, customer)).isEmpty();
    }

    /** The final period of an exempted name is kept, so an ignored character cannot join a link across it unseen. */
    @Test
    void aLinkFormedFromAFinalPeriodOfTheNameIsRefused() {
        Customer customer = customer("Ana Bono.");
        String body = "Ana Bono.\u200Dcom";

        assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", body, customer), customer, sender), "Bono");
    }

    @Test
    void aBlankSymbolOrAVeryLongWordDoesNotMoveANumberOutOfReach() {
        Customer customer = customer("Ana Precio");
        for (String padding : List.of("\u2800".repeat(40), "z".repeat(60))) {
            String body = "Ana Precio " + padding + " 100.";
            assertRefusedQuotingNothing(() -> assembler.bind(message("es-419", body, customer), customer, sender),
                    "100");
        }
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
        mapping(language, List.of("customer_name", "message_body"), content);
    }

    private void mapping(String language, List<String> parameterNames, TemplateContent content) {
        when(templates.resolve(SemanticTemplateIntent.GENERAL_FOLLOW_UP)).thenReturn(new TemplateMappingResolver.Resolved(
                new ResolvedTemplateMapping(SemanticTemplateIntent.GENERAL_FOLLOW_UP, "general_follow_up", language,
                        TemplateParameterFormat.POSITIONAL, parameterNames, content, 3)));
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
