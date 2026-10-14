// ABOUTME: Pins the approval/v1 fingerprint encoding and proves which changes void an approval (ADR 0024 §2).
// ABOUTME: Every bound field changes the value; the golden value fails if the canonical encoding drifts.
package io.github.stevdrey.dokene.messaging.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.Binding;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.TemplateRevision;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ApprovalFingerprintTest {
    private static final UUID TENANT = UUID.fromString("7a5c0000-0000-4000-8000-0000000b0001");
    private static final UUID MESSAGE = UUID.fromString("7a5c0000-0000-4000-8000-000000000002");
    private static final UUID CUSTOMER = UUID.fromString("7a5c0000-0000-4000-8000-0000000c0001");
    private static final UUID CONTACT = UUID.fromString("7a5c0000-0000-4000-8000-0000000c1001");
    private static final UUID INTEGRATION = UUID.fromString("7a5c0000-0000-4000-8000-0000000d0001");
    private static final long CONSENT = 4;

    private static final Binding BASE = new Binding(TENANT, MESSAGE, CUSTOMER, CONTACT, "+50600000001",
            MessageChannel.WHATSAPP, SemanticAction.REPEAT_PURCHASE_FOLLOW_UP, SemanticTemplateIntent.REPEAT_PURCHASE,
            "es-419", "Hola Ana", 7, CONSENT,
            new TemplateRevision(3, "repeat_purchase_v1", "es", List.of("customer_name", "product_name")),
            List.of("Ana", "Café 1 kg"), new SenderRevision(INTEGRATION, "100000000000001", "200000000000001"));

    @Test
    /** Independently reproducible: SHA-256 of each field as a 4 byte big endian length plus UTF-8 bytes. */
    void encodingIsStable() {
        assertThat(ApprovalFingerprint.of(BASE).value()).isEqualTo("b2ade4514b7480f1378d919f6d7436f3f3d3b61baba1e83e322514550cf78093");
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
                Map.entry("template version", b -> withTemplate(b, new TemplateRevision(4, "repeat_purchase_v1", "es", List.of("customer_name", "product_name")))),
                Map.entry("template name", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v2", "es", List.of("customer_name", "product_name")))),
                Map.entry("template language", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es_MX", List.of("customer_name", "product_name")))),
                Map.entry("parameter order", b -> withTemplate(b, new TemplateRevision(3, "repeat_purchase_v1", "es", List.of("product_name", "customer_name")))),
                Map.entry("parameter value", b -> withValues(b, List.of("Ana", "Café 2 kg"))),
                Map.entry("parameter value order", b -> withValues(b, List.of("Café 1 kg", "Ana"))),
                Map.entry("integration", b -> withSender(b, new SenderRevision(UUID.randomUUID(), "100000000000001", "200000000000001"))),
                Map.entry("sender number", b -> withSender(b, new SenderRevision(INTEGRATION, "100000000000002", "200000000000001"))),
                Map.entry("business account", b -> withSender(b, new SenderRevision(INTEGRATION, "100000000000001", "200000000000002"))));

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
        assertThatThrownBy(() -> new TemplateRevision(-1, "t", "es", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateRevision(3, "Repeat Purchase", "es", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateRevision(3, "repeat_purchase_v1", " ", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
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
