// ABOUTME: Pins mapping validation, the eligibility rule and the parameter schema check (issues 135 and 218).
// ABOUTME: Each sendable() condition is flipped alone so none can silently stop mattering.
package io.github.stevdrey.dokene.template.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateMappingTest {
    private static final TemplateContent CONTENT = new TemplateContent(null, "Synthetic body", null);
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final String WABA = "400000000000001";
    private static final UUID INTEGRATION = UUID.randomUUID();
    private static final ProviderTemplateSchema POSITIONAL_ONE =
            new ProviderTemplateSchema.BodyParameters(TemplateParameterFormat.POSITIONAL, List.of("1"), CONTENT);

    private static TemplateMapping mapping(String name, String language, List<String> parameters) {
        return TemplateMapping.create(new TenantId(UUID.randomUUID()), INTEGRATION,
                SemanticTemplateIntent.REPEAT_PURCHASE, name, language, parameters, true, NOW);
    }

    private static TemplateMapping approved() {
        return mapping("repeat_purchase_v1", "es_MX", List.of("customer_name"))
                .verifiedApproval(POSITIONAL_ONE, WABA, NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Repeat", "repeat-purchase", "repeat purchase", "a/b"})
    void rejectsMalformedProviderTemplateNames(String name) {
        assertThatThrownBy(() -> mapping(name, "es", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "es-419", "ES", "spanish", "es_mx"})
    void rejectsLanguagesOutsideMetasGrammar(String language) {
        assertThatThrownBy(() -> mapping("t", language, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadParameterSchemas() {
        assertThatThrownBy(() -> mapping("t", "es", List.of("a", "a"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapping("t", "es", List.of("Bad"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapping("t", "es", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anApprovedMappingForTheCurrentRevisionAndAccountIsSendable() {
        assertThat(approved().sendable(INTEGRATION, WABA, NOW.plusSeconds(60), MAX_AGE)).isTrue();
    }

    @Test
    void anyBrokenConditionMakesItIneligible() {
        TemplateMapping base = approved();
        assertThat(base.sendable(INTEGRATION, WABA, NOW.plus(MAX_AGE).plusSeconds(1), MAX_AGE)).isFalse();
        assertThat(base.sendable(INTEGRATION, WABA, NOW.minusSeconds(1), MAX_AGE)).isFalse();
        assertThat(base.sendable(UUID.randomUUID(), WABA, NOW, MAX_AGE)).isFalse();
        assertThat(base.sendable(INTEGRATION, "999999999999999", NOW, MAX_AGE)).isFalse();
        assertThat(base.withVersion(1).sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
        assertThat(base.edit(INTEGRATION, "repeat_purchase_v1", "es_MX", List.of("customer_name"), true, NOW)
                .sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
        assertThat(base.edit(INTEGRATION, "repeat_purchase_v1", "es_MX", List.of("customer_name"), false, NOW)
                .verifiedApproval(POSITIONAL_ONE, WABA, NOW).sendable(INTEGRATION, WABA, NOW, MAX_AGE))
                .isFalse();
        for (TemplateApprovalStatus status : TemplateApprovalStatus.values()) {
            if (status != TemplateApprovalStatus.APPROVED && status != TemplateApprovalStatus.UNVERIFIED
                    && !status.derivedFromSchema()) {
                assertThat(base.verified(status, WABA, NOW).sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
            }
        }
        assertThat(base.unverified(NOW).sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
    }

    @Test
    void evidenceIsCompleteOrAbsent() {
        assertThatThrownBy(() -> new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.APPROVED, null, null, null,
                TemplateParameterFormat.POSITIONAL, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.UNVERIFIED, NOW, WABA, 0L,
                null, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyApprovedEvidenceCarriesAParameterFormat() {
        assertThatThrownBy(() -> new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.SCHEMA_MISMATCH, NOW,
                WABA, 0L, TemplateParameterFormat.NAMED, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(approved().approval().parameterFormat()).isEqualTo(TemplateParameterFormat.POSITIONAL);
    }

    @Test
    void anApprovalWhoseSchemaWasNeverCheckedIsKeptButNeverSendable() {
        TemplateMapping base = mapping("t", "es", List.of("customer_name"));
        TemplateMapping unchecked = new TemplateMapping(base.id(), base.tenantId(), base.integrationId(), base.intent(),
                base.providerTemplateName(), base.language(), base.parameterNames(), true,
                new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.APPROVED, NOW, WABA, 0L, null, null),
                base.createdAt(), base.updatedAt(), 0);
        assertThat(unchecked.sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
    }

    /**
     * Issue 268: evidence stored by an earlier release checked an en_US template only with the Spanish vocabulary; its
     * fixed text is checked again with the current English rules before it can be sent.
     */
    @Test
    void evidenceFromEarlierRulesIsNotSendableWhenTheFixedTextNowFails() {
        TemplateMapping base = mapping("t", "en_US", List.of("message_body"));
        TemplateMapping stored = new TemplateMapping(base.id(), base.tenantId(), base.integrationId(), base.intent(),
                base.providerTemplateName(), base.language(), base.parameterNames(), true,
                new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.APPROVED, NOW, WABA, 0L,
                        TemplateParameterFormat.POSITIONAL, new TemplateContent(null, "Get a great deal: {{1}}", null)),
                base.createdAt(), base.updatedAt(), 0);
        assertThat(stored.sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();

        TemplateMapping plain = new TemplateMapping(base.id(), base.tenantId(), base.integrationId(), base.intent(),
                base.providerTemplateName(), base.language(), base.parameterNames(), true,
                new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.APPROVED, NOW, WABA, 0L,
                        TemplateParameterFormat.POSITIONAL, new TemplateContent(null, "Hello: {{1}}", null)),
                base.createdAt(), base.updatedAt(), 0);
        assertThat(plain.sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isTrue();
    }

    /** Issue 268: approval keeps the template's fixed text, and approval without it cannot be reviewed or sent. */
    @Test
    void approvalKeepsTheFixedTextAndIsNotSendableWithoutIt() {
        assertThat(approved().approval().content()).isEqualTo(CONTENT);

        TemplateMapping base = mapping("t", "es", List.of("customer_name"));
        TemplateMapping withoutContent = new TemplateMapping(base.id(), base.tenantId(), base.integrationId(),
                base.intent(), base.providerTemplateName(), base.language(), base.parameterNames(), true,
                new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.APPROVED, NOW, WABA, 0L,
                        TemplateParameterFormat.POSITIONAL, null),
                base.createdAt(), base.updatedAt(), 0);
        assertThat(withoutContent.sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();

        assertThat(approved().verified(TemplateApprovalStatus.PAUSED, WABA, NOW).approval().content()).isNull();
        assertThat(approved().unverified(NOW).approval().content()).isNull();
        assertThat(approved().edit(INTEGRATION, "repeat_purchase_v1", "es_MX", List.of("customer_name"), true, NOW)
                .approval().content()).isNull();
        assertThat(mapping("t", "es", List.of("customer_name", "product")).verifiedApproval(POSITIONAL_ONE, WABA, NOW)
                .approval().content()).isNull();
        assertThatThrownBy(() -> new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.APPROVED, NOW, WABA, 0L,
                null, CONTENT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void templateContentIsBoundedAndNeedsABody() {
        assertThatThrownBy(() -> new TemplateContent(null, " ", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateContent(null, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemplateContent("", "Hola", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateContent(null, "a\0b", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateContent(null, "a".repeat(TemplateContent.MAX_TEXT_LENGTH + 1), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TemplateContent("Aviso", "a".repeat(TemplateContent.MAX_TEXT_LENGTH), "Gracias").toString())
                .doesNotContain("Aviso", "Gracias");
        assertThatThrownBy(() -> new TemplateButton(TemplateButtonType.QUICK_REPLY, "Sí", "+50600000000"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateButton(TemplateButtonType.PHONE_NUMBER, "Llamar", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TemplateButton(TemplateButtonType.URL, " ", "https://example.test"))
                .isInstanceOf(IllegalArgumentException.class);
        TemplateButton reply = new TemplateButton(TemplateButtonType.QUICK_REPLY, "Sí", null);
        assertThatThrownBy(() -> new TemplateContent(null, "Hola", null,
                Collections.nCopies(TemplateContent.MAX_BUTTONS + 1, reply)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new TemplateContent(null, "Hola", null, List.of(reply)).toString()).doesNotContain("Sí");
    }

    @Test
    void approvalIsOnlyRecordedThroughTheSchemaCheck() {
        TemplateMapping base = mapping("t", "es", List.of("customer_name"));
        assertThatThrownBy(() -> base.verified(TemplateApprovalStatus.APPROVED, WABA, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        for (TemplateApprovalStatus status : TemplateApprovalStatus.values()) {
            if (status.derivedFromSchema()) {
                assertThatThrownBy(() -> base.verified(status, WABA, NOW)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    /** Without message_body the fixed body is the message, so it must fit a message body (issue 268). */
    @Test
    void aFixedBodyLongerThanAMessageBodyIsUnsupportedUnlessTheBodyIsAParameter() {
        ProviderTemplateSchema fits = fixedBody("é".repeat(MessageDraft.MAX_BODY_LENGTH));
        ProviderTemplateSchema tooLong = fixedBody("é".repeat(MessageDraft.MAX_BODY_LENGTH + 1));

        TemplateMapping fixed = mapping("hello_world", "en_US", List.of());
        assertThat(fixed.verifiedApproval(fits, WABA, NOW).sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isTrue();
        TemplateMapping refused = fixed.verifiedApproval(tooLong, WABA, NOW);
        assertThat(refused.approval().status()).isEqualTo(TemplateApprovalStatus.SCHEMA_UNSUPPORTED);
        assertThat(refused.approval().content()).isNull();
        assertThat(refused.sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();

        // With message_body the operator's text is sent as a parameter, so a long fixed body is not the limit.
        ProviderTemplateSchema withBody = new ProviderTemplateSchema.BodyParameters(TemplateParameterFormat.NAMED,
                List.of(TemplateMapping.Parameter.MESSAGE_BODY.parameterName()), new TemplateContent(null,
                        "é".repeat(MessageDraft.MAX_BODY_LENGTH) + " {{message_body}}", null));
        assertThat(mapping("t", "es", List.of(TemplateMapping.Parameter.MESSAGE_BODY.parameterName()))
                .verifiedApproval(withBody, WABA, NOW).approval().status())
                .isEqualTo(TemplateApprovalStatus.APPROVED);
    }

    /**
     * Issue 268: fixed text reaches every recipient unchanged and cannot be grounded per customer, so a link,
     * amount, percentage or offer in any part the recipient reads makes the template unsupported at refresh.
     */
    @Test
    void fixedTextTheSafetyChecksRefuseMakesTheTemplateUnsupported() {
        TemplateMapping fixed = mapping("hello_world", "en_US", List.of());
        List<TemplateContent> refused = List.of(
                new TemplateContent(null, "Visita tienda.example.com hoy", null),
                new TemplateContent("Oferta especial", "Gracias por tu compra.", null),
                new TemplateContent(null, "Gracias por tu compra.", "Ahorra $20 hoy"),
                new TemplateContent(null, "Gracias por tu compra.", "20% de descuento"),
                new TemplateContent(null, "Gracias por tu compra.", null,
                        List.of(new TemplateButton(TemplateButtonType.QUICK_REPLY, "Quiero mi descuento", null))));
        for (TemplateContent content : refused) {
            TemplateMapping verified = fixed.verifiedApproval(new ProviderTemplateSchema.BodyParameters(
                    TemplateParameterFormat.POSITIONAL, List.of(), content), WABA, NOW);
            assertThat(verified.approval().status()).as(content.body())
                    .isEqualTo(TemplateApprovalStatus.SCHEMA_UNSUPPORTED);
            assertThat(verified.approval().content()).isNull();
            assertThat(verified.sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
        }
        // English fixed text is checked with the English offer vocabulary as well (issue 268, part 2).
        TemplateMapping english = fixed.verifiedApproval(new ProviderTemplateSchema.BodyParameters(
                TemplateParameterFormat.POSITIONAL, List.of(), new TemplateContent(null, "Thanks for your order.",
                        "Get a great deal next week")), WABA, NOW);
        assertThat(english.approval().status()).isEqualTo(TemplateApprovalStatus.SCHEMA_UNSUPPORTED);
        // Button targets are structured and shown in review, so a static URL or phone target alone is fine.
        TemplateContent withTargets = new TemplateContent("Hello World", "Welcome and congratulations!! Thank you "
                + "for taking the time to test with us.", "WhatsApp Business Platform sample message", List.of(
                        new TemplateButton(TemplateButtonType.URL, "View catalog", "https://example.test/catalog"),
                        new TemplateButton(TemplateButtonType.PHONE_NUMBER, "Call us", "+50600000099")));
        assertThat(fixed.verifiedApproval(new ProviderTemplateSchema.BodyParameters(TemplateParameterFormat.POSITIONAL,
                List.of(), withTargets), WABA, NOW).sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isTrue();
    }

    private static ProviderTemplateSchema fixedBody(String body) {
        return new ProviderTemplateSchema.BodyParameters(TemplateParameterFormat.POSITIONAL, List.of(),
                new TemplateContent(null, body, null));
    }

    @Test
    void aMismatchedUnsupportedOrUnreadableSchemaIsRecordedButNeverSendable() {
        TemplateMapping base = mapping("t", "es", List.of("customer_name"));
        var cases = java.util.Map.of(
                new ProviderTemplateSchema.BodyParameters(TemplateParameterFormat.POSITIONAL, List.of("1", "2"), CONTENT),
                TemplateApprovalStatus.SCHEMA_MISMATCH,
                new ProviderTemplateSchema.Unsupported(), TemplateApprovalStatus.SCHEMA_UNSUPPORTED,
                new ProviderTemplateSchema.Unreadable(), TemplateApprovalStatus.SCHEMA_UNREADABLE);
        cases.forEach((schema, status) -> {
            TemplateMapping verified = base.verifiedApproval(schema, WABA, NOW);
            assertThat(verified.approval()).isEqualTo(
                    new TemplateMapping.ApprovalEvidence(status, NOW, WABA, 0L, null, null));
            assertThat(verified.sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
        });
        TemplateMapping named = base.verifiedApproval(new ProviderTemplateSchema.BodyParameters(
                TemplateParameterFormat.NAMED, List.of("customer_name"), CONTENT), WABA, NOW);
        assertThat(named.approval().status()).isEqualTo(TemplateApprovalStatus.APPROVED);
        assertThat(named.approval().parameterFormat()).isEqualTo(TemplateParameterFormat.NAMED);
        // Named placeholders are sent by name, so a template verified as NAMED is as sendable as a positional one.
        assertThat(named.sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isTrue();
    }

    @Test
    void theSingleValueRuleIsTheOneValidateApplies() {
        String emoji = "\uD83C\uDF81";
        assertThat(TemplateParameterValues.acceptable("Café")).isTrue();
        assertThat(TemplateParameterValues.acceptable("   ")).isTrue();
        assertThat(TemplateParameterValues.acceptable(emoji.repeat(TemplateParameterValues.MAX_LENGTH))).isTrue();
        for (String invalid : java.util.Arrays.asList(null, "", "a     b", "a\tb", "a\u2028b", "a\u202Eb",
                emoji.repeat(TemplateParameterValues.MAX_LENGTH + 1))) {
            assertThat(TemplateParameterValues.acceptable(invalid)).as("%s", invalid).isFalse();
        }
    }

    @Test
    void parameterValuesMustMatchTheSchemaExactly() {
        TemplateMapping two = mapping("t", "es", List.of("customer_name", "product"));
        assertThat(TemplateParameterValues.validate(two, List.of("Ana", "Café"))).containsExactly("Ana", "Café");
        for (List<String> invalid : List.of(List.of("Ana"), List.of("Ana", "Café", "extra"), List.of("Ana", ""),
                List.of("Ana", "a\nb"), List.of("Ana", "a     b"),
                List.of("Ana", "a\u202Eb"), List.of("Ana", "a\u200Bb"), List.of("Ana", "a\u200Eb"),
                List.of("Ana", "a\u2066b"), List.of("Ana", "a\u00ADb"), List.of("Ana", "a\u206Ab"),
                List.of("Ana", "a\u206Fb"), List.of("Ana", "a\uD83D\uDC01\uDB40\uDC01b"), List.of("Ana", "\uFEFFAna"), List.of("Ana", "a\u2060b"), List.of("Ana", "a\tb"),
                List.of("Ana", "a\u0085b"), List.of("Ana", "a\u009fb"), List.of("Ana", "a\u007fb"),
                List.of("Ana", "a\u2028b"), List.of("Ana", "a\u2029b"),
                List.of("Ana", "x".repeat(TemplateParameterValues.MAX_LENGTH + 1)))) {
            assertThatThrownBy(() -> TemplateParameterValues.validate(two, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // Four spaces are allowed (Meta refuses more than four); ZWJ emoji sequences and ZWNJ stay valid.
        assertThat(TemplateParameterValues.validate(two, List.of("a    b", "\uD83D\uDC68\u200D\uD83D\uDC69")))
                .hasSize(2);
        // Legitimate text keeps working: ZWNJ in Persian, accented Spanish, and an England flag (emoji tag sequence).
        assertThat(TemplateParameterValues.validate(two, List.of("\u0645\u06CC\u200C\u062E\u0648\u0627\u0647\u0645",
                "Mar\u00EDa Jos\u00E9 Pe\u00F1a"))).hasSize(2);
        assertThat(TemplateParameterValues.validate(two, List.of("\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65"
                + "\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F", "y"))).hasSize(2);
        assertThat(TemplateParameterValues.validate(two, List.of("Ñandú", "Café \uD83C\uDF81"))).hasSize(2);
        assertThatThrownBy(() -> TemplateParameterValues.validate(two, java.util.Arrays.asList("Ana", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TemplateParameterValues.validate(two, List.of("x".repeat(TemplateParameterValues.MAX_LENGTH), "y")))
                .hasSize(2);
        // Length counts characters, not UTF-16 units: 1024 emoji fit, 1025 do not.
        String emoji = "\uD83C\uDF81";
        assertThat(TemplateParameterValues.validate(two, List.of(emoji.repeat(TemplateParameterValues.MAX_LENGTH), "y")))
                .hasSize(2);
        assertThatThrownBy(() -> TemplateParameterValues.validate(two,
                List.of(emoji.repeat(TemplateParameterValues.MAX_LENGTH + 1), "y")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
