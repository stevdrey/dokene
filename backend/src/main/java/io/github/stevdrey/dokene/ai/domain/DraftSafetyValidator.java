package io.github.stevdrey.dokene.ai.domain;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /** Full localized number: dot/comma decimals or groups, or space/NBSP/narrow-NBSP thousands groups. */
    private static final String NUM = "\\d+(?:[.,]\\d+|[ \\u00a0\\u202f]\\d{3})*(?!\\d)";
    private static final String CURRENCY_CODES = "usd|crc|eur|mxn|cop|ars|clp|pen|brl|gtq|hnl|nio|pab|gbp|cad";
    private static final String CURRENCY_WORDS =
            "colones|dólares|dolares|pesos|soles|quetzales|lempiras|bolívares|bolivares|reales|libras";

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?iu)(?:\\b[a-z][a-z0-9+.-]*://\\S+|\\b(?:mailto|tel|sms|sip|geo|data|javascript|file|whatsapp|skype|callto):\\S+|\\bwww\\.\\S+|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?(?:/\\S*)?\\b|(?<![\\p{L}\\p{N}-])[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}(?:/\\S*)?(?![\\p{L}\\p{N}]))"
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
            Pattern.compile("(?i)\\b(descuento|descuentos|rebaja|rebajas|cupón|cupon|cupones|gratis|oferta|ofertas|promoción|promocion|promociones|liquidación|liquidacion|gratuito|gratuita|gratuitos|gratuitas|regalo|regalos|obsequio|obsequios|bono|bonos|cashback|reembolso|reembolsos|sin costo|black friday|2 por 1|\\d+\\s*x\\s*\\d+)\\b"),
            Pattern.compile("(?i)\\b(" + CURRENCY_CODES + "|" + CURRENCY_WORDS + "|precio|precios)\\b")
    );

    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile("(?i)\\b" + NUM + "\\s*%");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?i)(?:[\\$₡€£]|\\b(?:" + CURRENCY_CODES + "|" + CURRENCY_WORDS + ")\\b)\\s*" + NUM
                    + "|\\b" + NUM + "\\s*(?:[\\$₡€£]|\\b(?:" + CURRENCY_CODES + "|" + CURRENCY_WORDS + ")\\b)"
    );

    private static final Pattern NUMBER_PATTERN = Pattern.compile(NUM);
    private static final Pattern PRICE_TERM_NUMBER_PATTERN = Pattern.compile(
            "(?i)\\b(?:precios?|cuestan?|costos?|vale|valen|total)\\b[^\\d\\n]{0,20}?(" + NUM + ")"
    );

    private DraftSafetyValidator() {}

    /**
     * Validates a message draft against deterministic safety constraints and context grounding.
     * Evidence citations are grounded against the flattened context only (no fact-type provenance).
     *
     * @param draft the message draft to validate
     * @param allowedContextText concatenation of all trusted and untrusted text available during assembly
     * @return an empty Optional if the draft is safe, or an Optional containing a typed DraftSafetyViolation
     */
    public static Optional<DraftSafetyViolation> validate(MessageDraft draft, String allowedContextText) {
        return validate(draft, allowedContextText, null);
    }

    /**
     * Validates a message draft; when {@code grounding} is provided, each evidence citation is
     * bound to the authoritative field its label refers to instead of any context substring.
     */
    public static Optional<DraftSafetyViolation> validate(MessageDraft draft, String allowedContextText,
            DraftGroundingContext grounding) {
        Objects.requireNonNull(draft, "Draft is required");
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("body", draft.body());
        texts.put("rationale", draft.rationale());
        texts.put("warning", String.join("\n", draft.warnings()));
        for (DraftVariableEntry entry : draft.draftVariables().entries()) {
            texts.put("variable '" + entry.key() + "'", entry.key() + "\n" + entry.value());
        }
        texts.put("evidence", String.join("\n", draft.evidence()));

        String offerContext = grounding != null ? grounding.offerBearingText() : allowedContextText;
        Optional<DraftSafetyViolation> violation = validateTexts(texts, allowedContextText, offerContext);
        if (violation.isPresent()) {
            return violation;
        }
        return validateEvidence(draft.evidence(), allowedContextText, grounding);
    }

    /**
     * Validates the operator-visible rationale of a model refusal with the same URL, provider
     * identifier, offer, and quantity checks applied to draft text.
     */
    public static Optional<DraftSafetyViolation> validate(NoDraft noDraft, String allowedContextText) {
        return validate(noDraft, allowedContextText, null);
    }

    /**
     * As {@link #validate(NoDraft, String)}, grounding offers only in offer-bearing fields when
     * a {@code grounding} context is supplied.
     */
    public static Optional<DraftSafetyViolation> validate(NoDraft noDraft, String allowedContextText,
            DraftGroundingContext grounding) {
        Objects.requireNonNull(noDraft, "No-draft outcome is required");
        String offerContext = grounding != null ? grounding.offerBearingText() : allowedContextText;
        return validateTexts(Map.of("rationale", noDraft.rationale()), allowedContextText, offerContext);
    }

    private static Optional<DraftSafetyViolation> validateTexts(Map<String, String> texts, String allowedContextText,
            String offerContextText) {
        String contextLower = allowedContextText != null ? allowedContextText.toLowerCase(Locale.ROOT) : "";
        String offerContextLower = offerContextText != null ? offerContextText.toLowerCase(Locale.ROOT) : "";
        String normalizedContext = normalizeQuantities(offerContextLower);

        // 1. External links or URLs
        for (var entry : texts.entrySet()) {
            if (containsUrl(entry.getValue())) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_URL,
                        "Draft " + entry.getKey() + " contains unauthorized external link or URL"));
            }
        }

        // 2. Provider template identifiers
        for (var entry : texts.entrySet()) {
            if (containsProviderTemplate(entry.getValue())) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_PROVIDER_TEMPLATE,
                        "Draft " + entry.getKey() + " contains unauthorized provider template identifier"));
            }
        }

        String combinedDraftText = texts.values().stream()
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);

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

        // 3b. Numbers attached to price terms even when the currency marker is omitted
        Set<String> contextNumbers = new HashSet<>();
        Matcher contextPriceMatcher = PRICE_TERM_NUMBER_PATTERN.matcher(normalizedContext);
        while (contextPriceMatcher.find()) {
            contextNumbers.add(normalizeQuantities(contextPriceMatcher.group(1)));
        }
        for (String amountToken : contextAmounts) {
            Matcher numberMatcher = NUMBER_PATTERN.matcher(amountToken);
            if (numberMatcher.find()) {
                contextNumbers.add(normalizeQuantities(numberMatcher.group()));
            }
        }
        Matcher priceNumberMatcher = PRICE_TERM_NUMBER_PATTERN.matcher(combinedDraftText);
        while (priceNumberMatcher.find()) {
            String number = normalizeQuantities(priceNumberMatcher.group(1));
            if (!contextNumbers.contains(number)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_PRICE,
                        "Draft contains hallucinated price not grounded in context: '" + number + "'"));
            }
        }

        // 4. Offer symbols and terms grounding check
        for (String symbol : OFFER_SYMBOLS) {
            if (combinedDraftText.contains(symbol) && !offerContextLower.contains(symbol)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_OFFER_SYMBOL,
                        "Offer symbol '" + symbol + "' in draft is not present in context"));
            }
        }

        for (Pattern wordPattern : OFFER_WORD_PATTERNS) {
            Matcher draftMatcher = wordPattern.matcher(combinedDraftText);
            while (draftMatcher.find()) {
                String word = draftMatcher.group().toLowerCase(Locale.ROOT);
                Pattern specificPattern = Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b");
                if (!specificPattern.matcher(offerContextLower).find()) {
                    return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_OFFER_TERM,
                            "Offer term '" + word + "' in draft is not present in context"));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Evidence must be a structured {@code Label: Value} citation. With a grounding context the
     * value must match the authoritative field named by the label; unknown labels fall back to
     * the flattened context.
     */
    private static Optional<DraftSafetyViolation> validateEvidence(List<String> evidence, String allowedContextText,
            DraftGroundingContext grounding) {
        String contextLower = allowedContextText != null ? allowedContextText.toLowerCase(Locale.ROOT) : "";
        for (String evidenceItem : evidence) {
            String trimmed = evidenceItem.trim();
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0 || colonIdx >= trimmed.length() - 1) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNGROUNDED_EVIDENCE,
                        "Draft evidence item must be a structured citation with format 'Label: Value': '" + evidenceItem + "'"));
            }
            String label = trimmed.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colonIdx + 1).trim().toLowerCase(Locale.ROOT);
            DraftGroundingContext.EvidenceSource source = grounding != null ? grounding.sourceForLabel(label) : null;
            if (grounding != null && source == null) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNGROUNDED_EVIDENCE,
                        "Draft evidence item uses an unsupported label: '" + evidenceItem + "'"));
            }
            boolean grounded = !value.isEmpty() && (source == null
                    ? contextLower.contains(value)
                    : source.matches(value));
            if (!grounded) {
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
        return text.replaceAll("\\s+", " ").replaceAll("(?<=\\d)[ \\u00a0\\u202f](?=\\d{3})", "").replace(",", ".");
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
