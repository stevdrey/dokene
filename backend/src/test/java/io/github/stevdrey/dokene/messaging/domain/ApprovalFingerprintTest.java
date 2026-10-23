// ABOUTME: Pins the approval/v3 fingerprint encoding and proves which changes void an approval (ADR 0024 §2).
// ABOUTME: Every bound field changes the value; the golden value fails if the canonical encoding drifts.
package io.github.stevdrey.dokene.messaging.domain;

import static io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.ParameterFormat.NAMED;
import static io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.ParameterFormat.POSITIONAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.Binding;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.ButtonRevision;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.TemplateRevision;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ApprovalFingerprintTest {
    private static final String BODY_TEXT = "Synthetic body";
    private static final UUID TENANT = UUID.fromString("7a5c0000-0000-4000-8000-0000000b0001");
    private static final UUID MESSAGE = UUID.fromString("7a5c0000-0000-4000-8000-000000000002");
    private static final UUID CUSTOMER = UUID.fromString("7a5c0000-0000-4000-8000-0000000c0001");
    private static final UUID CONTACT = UUID.fromString("7a5c0000-0000-4000-8000-0000000c1001");
    private static final UUID INTEGRATION = UUID.fromString("7a5c0000-0000-4000-8000-0000000d0001");
    private static final long CONSENT = 4;

    private static final Binding BASE = new Binding(TENANT, MESSAGE, CUSTOMER, CONTACT, "+50600000001",
            MessageChannel.WHATSAPP, SemanticAction.REPEAT_PURCHASE_FOLLOW_UP, SemanticTemplateIntent.REPEAT_PURCHASE,
            "es-419", "Hola Ana", 7, CONSENT,
            new TemplateRevision(3, "repeat_purchase_v1", "es", POSITIONAL, List.of("customer_name", "product_name"), null, BODY_TEXT, null, List.of()),
            List.of("Ana", "Café 1 kg"), new SenderRevision(INTEGRATION, "100000000000001", "200000000000001", 1));

    @Test
    /** Independently reproducible: SHA-256 of each field as a 4 byte big endian length plus UTF-8 bytes. */
    void encodingIsStable() {
        assertThat(ApprovalFingerprint.of(BASE).value()).isEqualTo("9babce5d8b975809aaaae901b1cf0ae0763d6b469643ea09fdec93c23963e5d6");
    }

    /** Issue 268: a button the recipient can use is bound like the text, so changing it at Meta voids approvals. */
    @Test
    void everyButtonFieldAndTheirOrderVoidTheApproval() {
        ButtonRevision url = new ButtonRevision("URL", "Ver catálogo", "https://example.test/catalogo");
        ButtonRevision call = new ButtonRevision("PHONE_NUMBER", "Llamar", "+50600000099");
        Binding base = withTemplate(BASE, revision(List.of(url, call)));
        ApprovalFingerprint stored = ApprovalFingerprint.of(base);
        assertThat(stored.covers(withTemplate(BASE, revision(List.of(url, call))))).isTrue();
        for (List<ButtonRevision> changed : List.of(
                List.of(new ButtonRevision("URL", "Ver ofertas", url.value()), call),
                List.of(new ButtonRevision("URL", url.text(), "https://example.test/otro"), call),
                List.of(new ButtonRevision("QUICK_REPLY", url.text(), null), call),
                List.of(call, url),
                List.of(url))) {
            assertThat(stored.covers(withTemplate(BASE, revision(changed)))).as(changed.toString()).isFalse();
        }
    }

    private static TemplateRevision revision(List<ButtonRevision> buttons) {
        return new TemplateRevision(3, "repeat_purchase_v1", "es", POSITIONAL, List.of("customer_name", "product_name"),
                null, BODY_TEXT, null, buttons);
    }

    @Test
    void sameBindingCoversItselfAndIsDeterministic() {
        ApprovalFingerprint stored = ApprovalFingerprint.of(BASE);
        assertThat(stored.covers(copy(b -> b))).isTrue();
        assertThat(stored.value()).matches("^[0-9a-f]{64}$").isNotEqualTo(RequestFingerprint.of(
                MessageOperation.APPROVAL, List.of()));
    }

    @Test
    void everyBoundFieldVoidsTheApproval() {
        Map<String, UnaryOperator<Binding>> changes = Map.ofEntries(
                Map.entry("tenant", b -> with(b, UUID.randomUUID(), b.messageId(), b.contactId(), b.recipientPhone(), b.body(), b.consentRevision(), b.template(), b.sender(), b.templateIntent(), b.sourcePolicyVersion())),
                Map.entry("message", b -> with(b, b.tenantId(), UUID.randomUUID(), b.contactId(), b.recipientPhone(), b.body(), b.consentRevision(), b.template(), b.sender(), b.templateIntent(), b.sourcePolicyVersion())),
                Map.entry("contact", b -> with(b, b.tenantId(), b.messageId(), UUID.randomUUID(), b.recipientPhone(), b.body(), b.consentRevision(), b.template(), b.sender(), b.templateIntent(), b.sourcePolicyVersion())),
                Map.entry("recipient", b -> with(b, b.tenantId(), b.messageId(), b.contactId(), "+50600000002", b.body(), b.consentRevision(), b.template(), b.sender(), b.templateIntent(), b.sourcePolicyVersion())),
                Map.entry("body", b -> with(b, b.tenantId(), b.messageId(), b.contactId(), b.recipientPhone(), "Hola Ana!", b.consentRevision(), b.template(), b.sender(), b.templateIntent(), b.sourcePolicyVersion())),
                Map.entry("consent re-granted", b -> with(b, b.tenantId(), b.messageId(), b.contactId(), b.recipientPhone(), b.body(), CONSENT + 2, b.template(), b.sender(), b.templateIntent(), b.sourcePolicyVersion())),
                Map.entry("intent", b -> with(b, b.tenantId(), b.messageId(), b.contactId(), b.recipientPhone(), b.body(), b.consentRevision(), b.template(), b.sender(), SemanticTemplateIntent.GENERAL_FOLLOW_UP, b.sourcePolicyVersion())),
                Map.entry("policy snapshot", b -> with(b, b.tenantId(), b.messageId(), b.contactId(), b.recipientPhone(), b.body(), b.consentRevision(), b.template(), b.sender(), b.templateIntent(), 8)),
                Map.entry("template header", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es", POSITIONAL, List.of("customer_name", "product_name"), "Aviso", BODY_TEXT, null, List.of()))),
                Map.entry("template body text", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es", POSITIONAL, List.of("customer_name", "product_name"), null, BODY_TEXT + ".", null, List.of()))),
                Map.entry("template footer", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es", POSITIONAL, List.of("customer_name", "product_name"), null, BODY_TEXT, "Gracias", List.of()))),
                Map.entry("template button", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es", POSITIONAL, List.of("customer_name", "product_name"), null, BODY_TEXT, null, List.of(new ButtonRevision("QUICK_REPLY", "Sí", null))))),
                Map.entry("template version", b -> withTemplate(b, new TemplateRevision(4, "repeat_purchase_v1", "es", POSITIONAL, List.of("customer_name", "product_name"), null, BODY_TEXT, null, List.of()))),
                Map.entry("template name", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v2", "es", POSITIONAL, List.of("customer_name", "product_name"), null, BODY_TEXT, null, List.of()))),
                Map.entry("template language", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es_MX", POSITIONAL, List.of("customer_name", "product_name"), null, BODY_TEXT, null, List.of()))),
                // A template re-verified with named placeholders is sent differently, so it voids approvals.
                Map.entry("parameter format", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es", NAMED, List.of("customer_name", "product_name"), null, BODY_TEXT, null, List.of()))),
                Map.entry("parameter order", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es", POSITIONAL, List.of("product_name", "customer_name"), null, BODY_TEXT, null, List.of()))),
                Map.entry("parameter value", b -> withValues(b, List.of("Ana", "Café 2 kg"))),
                Map.entry("parameter value order", b -> withValues(b, List.of("Café 1 kg", "Ana"))),
                Map.entry("integration", b -> withSender(b, new SenderRevision(UUID.randomUUID(), "100000000000001", "200000000000001", 1))),
                Map.entry("sender number", b -> withSender(b, new SenderRevision(INTEGRATION, "100000000000002", "200000000000001", 1))),
                // Credential rotation in place (POST /api/integrations/whatsapp/credential-rotations) only bumps this revision.
                Map.entry("credential rotation", b -> withSender(b, new SenderRevision(INTEGRATION, "100000000000001", "200000000000001", 2))),
                Map.entry("business account", b -> withSender(b, new SenderRevision(INTEGRATION, "100000000000001", "200000000000002", 1))));

        ApprovalFingerprint stored = ApprovalFingerprint.of(BASE);
        changes.forEach((name, change) -> assertThat(stored.covers(change.apply(BASE))).as(name).isFalse());
    }

    @Test
    void lengthPrefixKeepsAdjacentParametersApart() {
        Binding joined = withValues(BASE, List.of("Ana C", "afé"));
        Binding split = withValues(BASE, List.of("Ana", " Café"));
        assertThat(ApprovalFingerprint.of(joined)).isNotEqualTo(ApprovalFingerprint.of(split));
    }

    @Test
    void everyTemplateParameterNeedsExactlyOneValue() {
        assertThatThrownBy(() -> withValues(BASE, List.of("Ana"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withValues(BASE, List.of("Ana", "Café", "extra")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> withValues(BASE, Arrays.asList("Ana", null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void consentRevisionStartsAtOne() {
        assertThatThrownBy(() -> with(BASE, TENANT, MESSAGE, CONTACT, BASE.recipientPhone(), BASE.body(), 0,
                BASE.template(), BASE.sender(), BASE.templateIntent(), 7)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIncompleteBindingsAndMalformedValues() {
        assertThatThrownBy(() -> with(BASE, TENANT, MESSAGE, CONTACT, null, BASE.body(), CONSENT, BASE.template(),
                BASE.sender(), BASE.templateIntent(), 7)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ApprovalFingerprint("ABC")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateRevision(-1, "t", "es", POSITIONAL, List.of(), null, BODY_TEXT, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateRevision(3, "Repeat Purchase", "es", POSITIONAL, List.of(), null, BODY_TEXT, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateRevision(3, "repeat_purchase_v1", " ", POSITIONAL, List.of(), null, BODY_TEXT, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Template language is required");
        assertThatThrownBy(() -> new TemplateRevision(3, "repeat_purchase_v1", "", POSITIONAL, List.of(), null, BODY_TEXT, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Template language is required");
        assertThatThrownBy(() -> new TemplateRevision(3, "repeat_purchase_v1", "es", null, List.of(), null, BODY_TEXT, null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    private static Binding copy(UnaryOperator<Binding> change) {
        return change.apply(BASE);
    }

    private static Binding with(Binding b, UUID tenant, UUID message, UUID contact, String phone, String body,
            long consent, TemplateRevision template, SenderRevision sender, SemanticTemplateIntent intent,
            long policyVersion) {
        return new Binding(tenant, message, b.customerId(), contact, phone, b.channel(), b.action(), intent,
                b.locale(), body, policyVersion, consent, template, b.parameterValues(), sender);
    }

    private static Binding withTemplate(Binding b, TemplateRevision template) {
        return with(b, b.tenantId(), b.messageId(), b.contactId(), b.recipientPhone(), b.body(),
                b.consentRevision(), template, b.sender(), b.templateIntent(), b.sourcePolicyVersion());
    }

    private static Binding withValues(Binding b, List<String> values) {
        return new Binding(b.tenantId(), b.messageId(), b.customerId(), b.contactId(), b.recipientPhone(),
                b.channel(), b.action(), b.templateIntent(), b.locale(), b.body(), b.sourcePolicyVersion(),
                b.consentRevision(), b.template(), values, b.sender());
    }

    private static Binding withSender(Binding b, SenderRevision sender) {
        return with(b, b.tenantId(), b.messageId(), b.contactId(), b.recipientPhone(), b.body(),
                b.consentRevision(), b.template(), sender, b.templateIntent(), b.sourcePolicyVersion());
    }
}
