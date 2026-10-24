// ABOUTME: Pins the resolved template mapping invariants shared with ApprovalFingerprint.TemplateRevision.
// ABOUTME: A mapping the approval could not bind (bad name, blank language, negative version) is never constructed.
package io.github.stevdrey.dokene.template.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.template.application.TemplateMappingResolver.ResolvedTemplateMapping;
import io.github.stevdrey.dokene.template.domain.TemplateContent;
import io.github.stevdrey.dokene.template.domain.TemplateParameterFormat;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TemplateMappingResolverTest {
    private static final TemplateContent CONTENT = new TemplateContent(null, "Synthetic body", null);
    @Test
    void acceptsAWellFormedMappingAndCopiesItsParameters() {
        var names = new ArrayList<>(List.of("customer_name"));

        var mapping = mapping("repeat_purchase_v1", "es_MX", names, 3);
        names.add("late_addition");

        assertThat(mapping.parameterNames()).containsExactly("customer_name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsABlankLanguage(String language) {
        assertThatThrownBy(() -> mapping("repeat_purchase_v1", language, List.of(), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Template language is required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Repeat_Purchase", "repeat-purchase", "repeat purchase"})
    void rejectsAMalformedProviderTemplateName(String name) {
        assertThatThrownBy(() -> mapping(name, "es", List.of(), 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Provider template name is malformed");
    }

    @Test
    void rejectsMissingValuesAndANegativeVersion() {
        assertThatThrownBy(() -> mapping("repeat_purchase_v1", null, List.of(), 3))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> mapping("repeat_purchase_v1", "es", List.of(), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresTheVerifiedParameterFormat() {
        assertThat(new ResolvedTemplateMapping(SemanticTemplateIntent.REPEAT_PURCHASE, "repeat_purchase_v1", "es",
                TemplateParameterFormat.NAMED, List.of("customer_name"), CONTENT, 3).parameterFormat())
                .isEqualTo(TemplateParameterFormat.NAMED);
        assertThatThrownBy(() -> new ResolvedTemplateMapping(SemanticTemplateIntent.REPEAT_PURCHASE,
                "repeat_purchase_v1", "es", null, List.of(), CONTENT, 3))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("Parameter format is required");
    }

    private static ResolvedTemplateMapping mapping(String name, String language, List<String> parameters,
            long version) {
        return new ResolvedTemplateMapping(SemanticTemplateIntent.REPEAT_PURCHASE, name, language,
                TemplateParameterFormat.POSITIONAL, parameters, CONTENT, version);
    }
}
