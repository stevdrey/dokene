// ABOUTME: Pins the placeholder scanner and the comparison of a provider schema with the stored names (issue 218).
// ABOUTME: Only an exact positional or named match is APPROVED; every other shape fails closed with a SCHEMA_* status.
package io.github.stevdrey.dokene.template.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.BodyParameters;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.Unreadable;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.Unsupported;
import io.github.stevdrey.dokene.template.domain.ProviderTemplateSchema.Verdict;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProviderTemplateSchemaTest {
    private static final List<String> TWO = List.of("customer_name", "message_body");

    private static Verdict positional(List<String> names, String... tokens) {
        return new BodyParameters(TemplateParameterFormat.POSITIONAL, List.of(tokens)).check(names);
    }

    private static Verdict named(List<String> names, String... tokens) {
        return new BodyParameters(TemplateParameterFormat.NAMED, List.of(tokens)).check(names);
    }

    private static final Verdict APPROVED_POSITIONAL =
            new Verdict(TemplateApprovalStatus.APPROVED, TemplateParameterFormat.POSITIONAL);
    private static final Verdict APPROVED_NAMED =
            new Verdict(TemplateApprovalStatus.APPROVED, TemplateParameterFormat.NAMED);
    private static final Verdict MISMATCH = new Verdict(TemplateApprovalStatus.SCHEMA_MISMATCH, null);
    private static final Verdict UNSUPPORTED = new Verdict(TemplateApprovalStatus.SCHEMA_UNSUPPORTED, null);

    @Test
    void contiguousPositionalPlaceholdersMatchingTheNameCountAreApproved() {
        assertThat(positional(TWO, "1", "2")).isEqualTo(APPROVED_POSITIONAL);
        assertThat(positional(TWO, "2", "1")).isEqualTo(APPROVED_POSITIONAL);
        assertThat(positional(List.of())).isEqualTo(APPROVED_POSITIONAL);
    }

    @Test
    void missingExtraOrNonContiguousPositionalPlaceholdersAreAMismatch() {
        assertThat(positional(TWO, "1")).isEqualTo(MISMATCH);
        assertThat(positional(TWO, "1", "2", "3")).isEqualTo(MISMATCH);
        assertThat(positional(TWO, "1", "3")).isEqualTo(MISMATCH);
        assertThat(positional(TWO, "0", "1")).isEqualTo(MISMATCH);
        assertThat(positional(TWO, "01", "2")).isEqualTo(MISMATCH);
        assertThat(positional(List.of(), "1")).isEqualTo(MISMATCH);
    }

    @Test
    void namedPlaceholdersMustBeExactlyTheStoredNamesInAnyOrder() {
        assertThat(named(TWO, "message_body", "customer_name")).isEqualTo(APPROVED_NAMED);
        assertThat(named(List.of())).isEqualTo(APPROVED_NAMED);
        assertThat(named(TWO, "customer_name")).isEqualTo(MISMATCH);
        assertThat(named(TWO, "customer_name", "message_body", "product")).isEqualTo(MISMATCH);
        assertThat(named(TWO, "customer_name", "body")).isEqualTo(MISMATCH);
        assertThat(named(List.of("a"), "a".repeat(33))).isEqualTo(MISMATCH);
    }

    @Test
    void theDeclaredFormatMustAgreeWithThePlaceholders() {
        assertThat(positional(TWO, "customer_name", "message_body")).isEqualTo(MISMATCH);
        assertThat(named(TWO, "1", "2")).isEqualTo(MISMATCH);
    }

    @Test
    void repeatedPlaceholdersAreUnsupported() {
        assertThat(positional(List.of("customer_name"), "1", "1")).isEqualTo(UNSUPPORTED);
        assertThat(named(List.of("customer_name"), "customer_name", "customer_name")).isEqualTo(UNSUPPORTED);
    }

    @Test
    void unsupportedAndUnreadableSchemasPassThrough() {
        assertThat(new Unsupported().check(TWO)).isEqualTo(UNSUPPORTED);
        assertThat(new Unreadable().check(TWO))
                .isEqualTo(new Verdict(TemplateApprovalStatus.SCHEMA_UNREADABLE, null));
    }

    @Test
    void theScannerReturnsTokensInOrder() {
        assertThat(ProviderTemplateSchema.placeholders("Hola {{1}}, {{2}}.")).contains(List.of("1", "2"));
        assertThat(ProviderTemplateSchema.placeholders("Hola {{customer_name}}")).contains(List.of("customer_name"));
        assertThat(ProviderTemplateSchema.placeholders("Sin variables { } ok")).contains(List.of());
        assertThat(ProviderTemplateSchema.placeholders("{{1}}}")).contains(List.of("1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{{1", "1}}", "{{}}", "{{{1}}}", "a }} b {{1}}", "{{ {{1}}", "{{a{b}}"})
    void malformedBracesAreRejected(String text) {
        assertThat(ProviderTemplateSchema.placeholders(text)).isEqualTo(Optional.empty());
    }

    @Test
    void theScannerIsBounded() {
        assertThat(ProviderTemplateSchema.placeholders("{{x}}".repeat(ProviderTemplateSchema.MAX_PLACEHOLDERS)))
                .isPresent();
        assertThat(ProviderTemplateSchema.placeholders("{{x}}".repeat(ProviderTemplateSchema.MAX_PLACEHOLDERS + 1)))
                .isEmpty();
        assertThat(ProviderTemplateSchema.placeholders(
                "{{" + "a".repeat(ProviderTemplateSchema.MAX_TOKEN_LENGTH) + "}}")).isPresent();
        assertThat(ProviderTemplateSchema.placeholders(
                "{{" + "a".repeat(ProviderTemplateSchema.MAX_TOKEN_LENGTH + 1) + "}}")).isEmpty();
    }

    @Test
    void verdictsKeepTheirInvariants() {
        assertThatThrownBy(() -> new Verdict(TemplateApprovalStatus.APPROVED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Verdict(TemplateApprovalStatus.SCHEMA_MISMATCH, TemplateParameterFormat.NAMED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Verdict(TemplateApprovalStatus.REJECTED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bodyParametersDoNotPrintTheirTokens() {
        assertThat(new BodyParameters(TemplateParameterFormat.NAMED, List.of("customer_name")).toString())
                .doesNotContain("customer_name");
    }
}
