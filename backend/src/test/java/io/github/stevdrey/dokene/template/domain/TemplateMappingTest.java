// ABOUTME: Pins mapping validation, the eligibility rule and the parameter schema check (issue 135).
// ABOUTME: Each sendable() condition is flipped alone so none can silently stop mattering.
package io.github.stevdrey.dokene.template.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateMappingTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final String WABA = "400000000000001";
    private static final UUID INTEGRATION = UUID.randomUUID();

    private static TemplateMapping mapping(String name, String language, List<String> parameters) {
        return TemplateMapping.create(new TenantId(UUID.randomUUID()), INTEGRATION,
                SemanticTemplateIntent.REPEAT_PURCHASE, name, language, parameters, true, NOW);
    }

    private static TemplateMapping approved() {
        return mapping("repeat_purchase_v1", "es_MX", List.of("customer_name"))
                .verified(TemplateApprovalStatus.APPROVED, WABA, NOW);
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
                .verified(TemplateApprovalStatus.APPROVED, WABA, NOW).sendable(INTEGRATION, WABA, NOW, MAX_AGE))
                .isFalse();
        for (TemplateApprovalStatus status : TemplateApprovalStatus.values()) {
            if (status != TemplateApprovalStatus.APPROVED && status != TemplateApprovalStatus.UNVERIFIED) {
                assertThat(base.verified(status, WABA, NOW).sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
            }
        }
        assertThat(base.unverified(NOW).sendable(INTEGRATION, WABA, NOW, MAX_AGE)).isFalse();
    }

    @Test
    void evidenceIsCompleteOrAbsent() {
        assertThatThrownBy(() -> new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.APPROVED, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TemplateMapping.ApprovalEvidence(TemplateApprovalStatus.UNVERIFIED, NOW, WABA, 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parameterValuesMustMatchTheSchemaExactly() {
        TemplateMapping two = mapping("t", "es", List.of("customer_name", "product"));
        assertThat(TemplateParameterValues.validate(two, List.of("Ana", "Café"))).containsExactly("Ana", "Café");
        for (List<String> invalid : List.of(List.of("Ana"), List.of("Ana", "Café", "extra"), List.of("Ana", ""),
                List.of("Ana", "a\nb"), List.of("Ana", "a    b"),
                List.of("Ana", "x".repeat(TemplateParameterValues.MAX_LENGTH + 1)))) {
            assertThatThrownBy(() -> TemplateParameterValues.validate(two, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> TemplateParameterValues.validate(two, java.util.Arrays.asList("Ana", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TemplateParameterValues.validate(two, List.of("x".repeat(TemplateParameterValues.MAX_LENGTH), "y")))
                .hasSize(2);
    }
}
