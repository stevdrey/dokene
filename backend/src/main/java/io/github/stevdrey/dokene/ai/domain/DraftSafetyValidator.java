package io.github.stevdrey.dokene.ai.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
     * @return an empty Optional if the draft is safe, or an Optional containing a typed DraftSafetyViolation
     */
    public static Optional<DraftSafetyViolation> validate(MessageDraft draft, String allowedContextText) {
        Objects.requireNonNull(draft, "Draft is required");
        String contextLower = allowedContextText != null ? allowedContextText.toLowerCase(Locale.ROOT) : "";
        String normalizedContext = normalizeQuantities(contextLower);

        // 1. Check for external links or URLs in body, rationale, warnings, draft variables, and evidence
        if (containsUrl(draft.body())) {
            return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_URL,
                    "Draft body contains unauthorized external link or URL"));
        }
        if (containsUrl(draft.rationale())) {
            return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_URL,
                    "Draft rationale contains unauthorized external link or URL"));
        }
        for (String warning : draft.warnings()) {
            if (containsUrl(warning)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_URL,
                        "Draft warning contains unauthorized external link or URL"));
            }
        }
        for (DraftVariableEntry entry : draft.draftVariables().entries()) {
            if (containsUrl(entry.value()) || containsUrl(entry.key())) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_URL,
                        "Draft variable '" + entry.key() + "' contains unauthorized external link or URL"));
            }
        }
        for (String evidenceItem : draft.evidence()) {
            if (containsUrl(evidenceItem)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_URL,
                        "Draft evidence contains unauthorized external link or URL"));
            }
        }

        // 2. Check for provider template identifiers in body, rationale, warnings, draft variables, and evidence
        if (containsProviderTemplate(draft.body())) {
            return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_PROVIDER_TEMPLATE,
                    "Draft body contains unauthorized provider template identifier"));
        }
        if (containsProviderTemplate(draft.rationale())) {
            return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_PROVIDER_TEMPLATE,
                    "Draft rationale contains unauthorized provider template identifier"));
        }
        for (String warning : draft.warnings()) {
            if (containsProviderTemplate(warning)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_PROVIDER_TEMPLATE,
                        "Draft warning contains unauthorized provider template identifier"));
            }
        }
        for (DraftVariableEntry entry : draft.draftVariables().entries()) {
            if (containsProviderTemplate(entry.value()) || containsProviderTemplate(entry.key())) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_PROVIDER_TEMPLATE,
                        "Draft variable '" + entry.key() + "' contains unauthorized provider template identifier"));
            }
        }
        for (String evidenceItem : draft.evidence()) {
            if (containsProviderTemplate(evidenceItem)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_PROVIDER_TEMPLATE,
                        "Draft evidence contains unauthorized provider template identifier"));
            }
        }

        // 3. Check for offer keywords, symbols, and currency terms
        List<String> combinedParts = new ArrayList<>();
        combinedParts.add(draft.body());
        if (draft.rationale() != null) {
            combinedParts.add(draft.rationale());
        }
        combinedParts.addAll(draft.warnings());
        for (DraftVariableEntry entry : draft.draftVariables().entries()) {
            combinedParts.add(entry.value());
        }
        for (String evidenceItem : draft.evidence()) {
            combinedParts.add(evidenceItem);
        }
        String combinedDraftText = String.join(" ", combinedParts).toLowerCase(Locale.ROOT);

        // 3. Token-exact quantity grounding: percentages and monetary amounts
        Set<String> contextPercentages = extractTokens(PERCENTAGE_PATTERN, normalizedContext);
        Matcher draftPctMatcher = PERCENTAGE_PATTERN.matcher(combinedDraftText);
        while (draftPctMatcher.find()) {
            String pct = normalizeQuantities(draftPctMatcher.group());
            if (!contextPercentages.contains(pct)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_PERCENTAGE,
                        "Draft contains hallucinated percentage or discount amount not grounded in context: '" + draftPctMatcher.group().trim() + "'"));
            }
        }

        Set<String> contextAmounts = extractTokens(AMOUNT_PATTERN, normalizedContext);
        Matcher draftAmtMatcher = AMOUNT_PATTERN.matcher(combinedDraftText);
        while (draftAmtMatcher.find()) {
            String amt = normalizeQuantities(draftAmtMatcher.group());
            if (!contextAmounts.contains(amt)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_PRICE,
                        "Draft contains hallucinated price or monetary amount not grounded in context: '" + draftAmtMatcher.group().trim() + "'"));
            }
        }

        // 4. Offer symbols and terms grounding check
        for (String symbol : OFFER_SYMBOLS) {
            if (combinedDraftText.contains(symbol) && !contextLower.contains(symbol)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_OFFER_SYMBOL,
                        "Offer symbol '" + symbol + "' in draft is not present in context"));
            }
        }

        for (Pattern wordPattern : OFFER_WORD_PATTERNS) {
            Matcher draftMatcher = wordPattern.matcher(combinedDraftText);
            while (draftMatcher.find()) {
                String word = draftMatcher.group().toLowerCase(Locale.ROOT);
                Pattern specificPattern = Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b");
                if (!specificPattern.matcher(contextLower).find()) {
                    return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_OFFER_TERM,
                            "Offer term '" + word + "' in draft is not present in context"));
                }
            }
        }

        // 5. Evidence grounding check: factual claims in evidence must appear in context and be structured
        for (String evidenceItem : draft.evidence()) {
            String trimmed = evidenceItem.trim();
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0 || colonIdx >= trimmed.length() - 1) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNGROUNDED_EVIDENCE,
                        "Draft evidence item must be a structured citation with format 'Label: Value': '" + evidenceItem + "'"));
            }
            String value = trimmed.substring(colonIdx + 1).trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty() || !contextLower.contains(value)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNGROUNDED_EVIDENCE,
                        "Draft evidence item contains factual claim not found in context: '" + evidenceItem + "'"));
            }
        }

        return Optional.empty();
    }

    private static Set<String> extractTokens(Pattern pattern, String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new HashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            tokens.add(normalizeQuantities(matcher.group()));
        }
        return tokens;
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
