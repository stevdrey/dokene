package io.github.stevdrey.dokene.ai.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic safety validator for follow-up message drafts.
 * Prohibits invented discounts, prices, external links, and provider template identifiers.
 */
public final class DraftSafetyValidator {

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?i)(?:\\b[a-z][a-z0-9+.-]*://\\S+|\\bwww\\.\\S+|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?(?:/\\S*)?\\b|\\b[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?:/\\S*)?\\b)"
    );

    private static final Pattern PROVIDER_TEMPLATE_PATTERN = Pattern.compile(
            "(?i)\\b(meta_[a-z0-9_]+|whatsapp_[a-z0-9_]+|waba_[a-z0-9_]+|hsm_[a-z0-9_]+|[a-z0-9_]*template_[a-z0-9_]+|template_id|hsm_id|waba_id)\\b"
    );

    private static final List<String> OFFER_SYMBOLS = List.of(
            "%",
            "$",
            "₡",
            "€",
            "£"
    );

    private static final List<Pattern> OFFER_WORD_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(descuento|descuentos|rebaja|rebajas|cupón|cupon|cupones|gratis|oferta|ofertas|promoción|promocion|promociones|liquidación|liquidacion)\\b"),
            Pattern.compile("(?i)\\b(usd|crc|eur|dólares|dolares|colones|precio|precios)\\b")
    );

    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile("(?i)\\b\\d+(?:[.,]\\d+)?\\s*%");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?i)(?:[\\$₡€£]|\\b(?:usd|crc|eur)\\b)\\s*\\d+(?:[.,]\\d+)?|\\b\\d+(?:[.,]\\d+)?\\s*(?:[\\$₡€£]|\\b(?:usd|crc|eur|colones|dólares|dolares)\\b)"
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
        String normalizedContext = normalizeQuantities(contextLower);

        // 1. Check for external links or URLs in body, draft variables, and evidence
        if (containsUrl(draft.body())) {
            return Optional.of("Draft body contains unauthorized external link or URL");
        }
        for (DraftVariableEntry entry : draft.draftVariables().entries()) {
            if (containsUrl(entry.value()) || containsUrl(entry.key())) {
                return Optional.of("Draft variable '" + entry.key() + "' contains unauthorized external link or URL");
            }
        }
        for (String evidenceItem : draft.evidence()) {
            if (containsUrl(evidenceItem)) {
                return Optional.of("Draft evidence contains unauthorized external link or URL");
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
        for (String evidenceItem : draft.evidence()) {
            if (containsProviderTemplate(evidenceItem)) {
                return Optional.of("Draft evidence contains unauthorized provider template identifier");
            }
        }

        // 3. Check for offer keywords, symbols, and currency terms
        List<String> combinedParts = new ArrayList<>();
        combinedParts.add(draft.body());
        for (DraftVariableEntry entry : draft.draftVariables().entries()) {
            combinedParts.add(entry.value());
        }
        for (String evidenceItem : draft.evidence()) {
            combinedParts.add(evidenceItem);
        }
        String combinedDraftText = String.join(" ", combinedParts).toLowerCase(Locale.ROOT);

        for (String symbol : OFFER_SYMBOLS) {
            if (combinedDraftText.contains(symbol) && !contextLower.contains(symbol)) {
                return Optional.of("Draft contains hallucinated offer, discount, or price term: '" + symbol + "'");
            }
        }

        for (Pattern wordPattern : OFFER_WORD_PATTERNS) {
            Matcher draftMatcher = wordPattern.matcher(combinedDraftText);
            while (draftMatcher.find()) {
                String word = draftMatcher.group();
                if (!wordPattern.matcher(contextLower).find()) {
                    return Optional.of("Draft contains hallucinated offer, discount, or price term: '" + word + "'");
                }
            }
        }

        // 4. Exact quantity grounding: percentages and monetary amounts
        Matcher draftPctMatcher = PERCENTAGE_PATTERN.matcher(combinedDraftText);
        while (draftPctMatcher.find()) {
            String pct = normalizeQuantities(draftPctMatcher.group());
            if (!normalizedContext.contains(pct)) {
                return Optional.of("Draft contains hallucinated percentage or discount amount not grounded in context: '" + draftPctMatcher.group().trim() + "'");
            }
        }

        Matcher draftAmtMatcher = AMOUNT_PATTERN.matcher(combinedDraftText);
        while (draftAmtMatcher.find()) {
            String amt = normalizeQuantities(draftAmtMatcher.group());
            if (!normalizedContext.contains(amt)) {
                return Optional.of("Draft contains hallucinated price or monetary amount not grounded in context: '" + draftAmtMatcher.group().trim() + "'");
            }
        }

        // 5. Evidence grounding check: factual claims in evidence must appear in context
        for (String evidenceItem : draft.evidence()) {
            String trimmed = evidenceItem.trim();
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx >= 0 && colonIdx < trimmed.length() - 1) {
                String value = trimmed.substring(colonIdx + 1).trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty() && !contextLower.contains(value)) {
                    return Optional.of("Draft evidence item contains factual claim not found in context: '" + evidenceItem + "'");
                }
            }
        }

        return Optional.empty();
    }

    private static String normalizeQuantities(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").replace(",", ".");
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
