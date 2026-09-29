package io.github.stevdrey.dokene.ai.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic safety validator for follow-up message drafts.
 * Prohibits invented discounts, prices, external links, and provider template identifiers.
 */
public final class DraftSafetyValidator {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)\\b(https?://|www\\.|bit\\.ly/|[a-z0-9.-]+\\.(com|org|net|co|io|dev|app|me)\\b)"
    );

    private static final Pattern PROVIDER_TEMPLATE_PATTERN = Pattern.compile(
            "(?i)\\b(meta_[a-z0-9_]+|whatsapp_[a-z0-9_]+|[a-z0-9_]+_template_[0-9]+|template_id)\\b"
    );

    private static final List<String> OFFER_TERMS = List.of(
            "%",
            "descuento",
            "rebaja",
            "cupón",
            "cupon",
            "gratis",
            "oferta especial",
            "promoción",
            "promocion",
            "$"
    );

    private DraftSafetyValidator() {}

    /**
     * Validates a message draft against deterministic safety constraints and context grounding.
     *
     * @param draft the message draft to validate
     * @param allowedContextText concatenation of all trusted and untrusted text available during assembly
     * @return an empty Optional if the draft is safe, or an Optional containing a violation description
     */
    public static Optional<String> validate(MessageDraft draft, String allowedContextText) {
        Objects.requireNonNull(draft, "Draft is required");
        String contextLower = allowedContextText != null ? allowedContextText.toLowerCase(Locale.ROOT) : "";

        // 1. Check for external links or URLs in body and draft variables
        if (containsUrl(draft.body())) {
            return Optional.of("Draft body contains unauthorized external link or URL");
        }
        for (DraftVariableEntry entry : draft.draftVariables().entries()) {
            if (containsUrl(entry.value()) || containsUrl(entry.key())) {
                return Optional.of("Draft variable '" + entry.key() + "' contains unauthorized external link or URL");
            }
        }

        // 2. Check for provider template identifiers
        if (containsProviderTemplate(draft.body())) {
            return Optional.of("Draft body contains unauthorized provider template identifier");
        }
        for (DraftVariableEntry entry : draft.draftVariables().entries()) {
            if (containsProviderTemplate(entry.value()) || containsProviderTemplate(entry.key())) {
                return Optional.of("Draft variable '" + entry.key() + "' contains unauthorized provider template identifier");
            }
        }

        // 3. Check for hallucinated offers, discounts, or prices not grounded in context
        String combinedDraftText = (draft.body() + " " + String.join(" ",
                draft.draftVariables().entries().stream().map(DraftVariableEntry::value).toList()))
                .toLowerCase(Locale.ROOT);

        for (String term : OFFER_TERMS) {
            if (combinedDraftText.contains(term) && !contextLower.contains(term)) {
                return Optional.of("Draft contains hallucinated offer, discount, or price term: '" + term + "'");
            }
        }

        return Optional.empty();
    }

    public static boolean containsUrl(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return URL_PATTERN.matcher(text).find();
    }

    public static boolean containsProviderTemplate(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return PROVIDER_TEMPLATE_PATTERN.matcher(text).find();
    }
}
