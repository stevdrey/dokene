package io.github.stevdrey.dokene.ai.domain;

import java.text.Normalizer;
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
import java.util.stream.Stream;

/**
 * Deterministic safety validator for follow-up message drafts.
 * Prohibits invented discounts, prices, external links, and provider template identifiers.
 */
public final class DraftSafetyValidator {

    /** Full localized number: dot/comma decimals or groups, or space/NBSP/narrow-NBSP thousands groups. */
    /** Whitespace including NBSP, figure space, narrow NBSP and thin space (not matched by default {@code \\s}). */
    private static final String SP = "[\\s\\u00a0\\u2007\\u202f\\u2009]*";
    private static final String NUM = "\\d+(?:[.,]\\d+|[ \\u00a0\\u202f]\\d{3})*(?!\\d)";
    private static final String CURRENCY_CODES = "usd|crc|eur|mxn|cop|ars|clp|pen|brl|gtq|hnl|nio|pab|gbp|cad|jpy|cny|krw|inr";
    /** Currency symbols recognised after NFKC folding (fullwidth forms such as {@code ＄} and {@code ￥} fold to these). */
    private static final String CURRENCY_SYMBOLS = "$₡€£¥₩₹₽₱₪฿₫₦₲₴₺¢";
    private static final String CURRENCY_SYMBOL_CLASS = "[\\" + CURRENCY_SYMBOLS + "]";
    private static final String CURRENCY_WORDS =
            "colones|dólares|dolares|pesos|soles|quetzales|lempiras|bolívares|bolivares|reales|libras";

    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?iu)(?:\\b[a-z][a-z0-9+.-]*://\\S+|\\b(?:mailto|tel|sms|sip|geo|data|javascript|file|whatsapp|skype|callto):\\S+|\\bwww\\.\\S+|\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?(?:/\\S*)?\\b|(?<![\\p{L}\\p{N}-])[\\p{L}\\p{N}-]+(?:\\.[\\p{L}\\p{N}-]+)*\\.\\p{L}{2,}(?:/\\S*)?(?![\\p{L}\\p{N}]))"
    );

    private static final Pattern PROVIDER_TEMPLATE_PATTERN = Pattern.compile(
            "(?i)\\b(meta_[a-z0-9_]+|whatsapp_[a-z0-9_]+|waba_[a-z0-9_]+|hsm_[a-z0-9_]+|[a-z0-9_]*template_[a-z0-9_]+|template_id|hsm_id|waba_id)\\b"
    );

    private static final List<String> OFFER_SYMBOLS = Stream.concat(
            Stream.of("%"), CURRENCY_SYMBOLS.chars().mapToObj(Character::toString)).toList();

    /** Percentage markers written out after a number ("10 por ciento", "10 pct"); canonicalised to {@code %}. */
    private static final Pattern PERCENT_WORD_PATTERN = Pattern.compile(
            "(?<=\\d)" + SP + "(?:por\\s*ciento|porciento|pct)(?:\\.(?!\\p{L}))?(?![\\p{L}\\p{N}])(?!\\.\\p{L})");
    private static final Pattern FORMAT_CHARACTER_PATTERN = Pattern.compile("[\\p{Cf}\\x{034F}\\x{115F}\\x{1160}\\x{180B}-\\x{180D}\\x{180F}\\x{3164}\\x{FE00}-\\x{FE0F}\\x{FFA0}\\x{E0100}-\\x{E01EF}]");
    private static final Pattern WORD_TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}@]+");
    /** Compact numeric promotions ("2x1") are offer vocabulary as written; folding them would hide them. */
    private static final Pattern COMPACT_PROMOTION_PATTERN = Pattern.compile("\\d+x\\d+");
    private static final Pattern NUMERIC_SUFFIX_PATTERN = Pattern.compile("(?<=\\p{L})\\p{N}+$");
    private static final Pattern LEET_DIGIT_PATTERN = Pattern.compile("[013457@]");

    /** Cyrillic and Greek letters that render like Latin ones; folded so homoglyph spellings match the vocabularies. */
    private static final Map<Character, Character> CONFUSABLES = confusables();

    private static final Pattern DEFANGED_DOT_PATTERN = Pattern.compile("\\s*[\\[({]\\s*(?:\\.|dot)\\s*[\\])}]\\s*");
    /**
     * An ideographic full stop between letters or digits is treated as a dot for URL detection. Prose never uses it
     * between Spanish words, and sentence-case heuristics ("Hola。Gracias" vs "promo。DEV") are bypassable.
     */
    private static final Pattern IDEOGRAPHIC_DOT_PATTERN = Pattern.compile("(?<=[\\p{L}\\p{N}-])[。｡](?=[\\p{L}\\p{N}-])");
    private static final Pattern DEFANGED_SCHEME_PATTERN = Pattern.compile("\\bhxxp(s?)\\b");
    private static final Pattern DEFANGED_COLON_PATTERN = Pattern.compile("\\s*[\\[({]\\s*:\\s*(?://)?\\s*[\\])}]\\s*");

    private static final List<Pattern> OFFER_WORD_PATTERNS = List.of(
            Pattern.compile("(?i)\\b(descuento|descuentos|rebaja|rebajas|cupón|cupon|cupones|gratis|oferta|ofertas|promoción|promocion|promociones|liquidación|liquidacion|gratuito|gratuita|gratuitos|gratuitas|regalo|regalos|obsequio|obsequios|bono|bonos|cashback|reembolso|reembolsos|sin costo|black friday|2 por 1|\\d+\\s*x\\s*\\d+)\\b"),
            Pattern.compile("(?i)\\b(" + CURRENCY_CODES + "|" + CURRENCY_WORDS + "|precio|precios)(?!\\p{L})")
    );

    /**
     * A refusal rationale may state that there is no offer ("No hay una oferta relevante"). Only a negation
     * immediately attached to an offer term is neutralised, and only for refusal rationales; amounts, symbols, links
     * and any affirmative or unattached offer term keep being rejected.
     */
    private static final String NEGATABLE_OFFER_TERMS = "(?:descuentos?|rebajas?|cup[oó]n|cupones|promoci[oó]n(?:es)?|ofertas?|regalos?|bonos?|obsequios?"
            + "|cashback|liquidaci[oó]n(?:es)?|reembolsos?|gratis|gratuit[oa]s?|(?:opciones?\\s+)?sin\\s+costo|precios?|black\\s+friday"
            + "|2\\s+por\\s+1|\\d+\\s*x\\s*\\d+|" + CURRENCY_CODES + "|" + CURRENCY_WORDS + ")";
    private static final String NEGATION_MODIFIERS = "(?:(?:una?|unos|unas|ning[uú]n[a]?|alguna?|relevantes?|disponibles?|vigentes?|aplicables?)\\s+)";
    private static final Pattern NEGATED_OFFER_PATTERN = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:no\\s+(?:hay|existe|existen|tenemos|tiene|se\\s+encontr(?:ó|o|aron)|aplica)|sin|ning[uú]n[a]?)"
                    + "\\s+" + NEGATION_MODIFIERS + "{0,2}" + NEGATABLE_OFFER_TERMS
                    + "(?:(?:\\s+(?:relevantes?|disponibles?|vigentes?|aplicables?))?\\s*,?\\s+(?:ni|o)\\s+"
                    + NEGATION_MODIFIERS + "{0,2}" + NEGATABLE_OFFER_TERMS + ")*"
                    + "(?![\\p{L}\\p{N}])");

    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile("(?i)\\b" + NUM + SP + "%");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?i)(?:" + CURRENCY_SYMBOL_CLASS + "|\\b(?:" + CURRENCY_CODES + "|" + CURRENCY_WORDS + ")(?!\\p{L}))" + SP + NUM
                    + "|\\b" + NUM + SP + "(?:" + CURRENCY_SYMBOL_CLASS + "|(?:" + CURRENCY_CODES + "|" + CURRENCY_WORDS + ")(?!\\p{L}))"
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
        return validateTexts(Map.of("rationale", noDraft.rationale()), allowedContextText, offerContext, true);
    }

    /**
     * Validates the operator-visible rationale of a model refusal ({@code NoRecommendation}) with the same URL,
     * provider identifier, offer and quantity checks applied to draft text.
     */
    public static Optional<DraftSafetyViolation> validate(NoRecommendation refusal, String allowedContextText,
            DraftGroundingContext grounding) {
        Objects.requireNonNull(refusal, "No-recommendation outcome is required");
        String offerContext = grounding != null ? grounding.offerBearingText() : allowedContextText;
        return validateTexts(Map.of("rationale", refusal.rationale()), allowedContextText, offerContext, true);
    }

    /**
     * Validates the operator-visible rationale and draft variables of an action recommendation with the same
     * URL, provider identifier, offer and quantity checks applied to draft text.
     */
    public static Optional<DraftSafetyViolation> validate(ActionRecommendation recommendation,
            String allowedContextText, DraftGroundingContext grounding) {
        Objects.requireNonNull(recommendation, "Recommendation is required");
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("rationale", recommendation.rationale());
        for (DraftVariableEntry entry : recommendation.draftVariables().entries()) {
            texts.put("variable '" + entry.key() + "'", entry.key() + "\n" + entry.value());
        }
        String offerContext = grounding != null ? grounding.offerBearingText() : allowedContextText;
        return validateTexts(texts, allowedContextText, offerContext, false);
    }

    private static Optional<DraftSafetyViolation> validateTexts(Map<String, String> texts, String allowedContextText,
            String offerContextText) {
        return validateTexts(texts, allowedContextText, offerContextText, false);
    }

    private static Optional<DraftSafetyViolation> validateTexts(Map<String, String> texts, String allowedContextText,
            String offerContextText, boolean refusalRationale) {
        String offerContextLower = canonicalize(offerContextText);
        String normalizedContext = normalizeQuantities(offerContextLower);
        Map<String, String> canonicalTexts = new LinkedHashMap<>();
        Map<String, String> urlTexts = new LinkedHashMap<>();
        texts.forEach((key, value) -> {
            canonicalTexts.put(key, canonicalize(value));
            // URLs are scanned before the written-percentage rewrite so "10 pct[.]com" keeps its domain.
            urlTexts.put(key, canonicalize(value, false));
        });
        texts = canonicalTexts;

        // 1. External links or URLs
        for (var entry : urlTexts.entrySet()) {
            if (containsCanonicalUrl(entry.getValue())) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_URL,
                        "Draft " + entry.getKey() + " contains unauthorized external link or URL"));
            }
        }

        // 2. Provider template identifiers
        for (var entry : texts.entrySet()) {
            if (containsCanonicalProviderTemplate(entry.getValue())) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNAUTHORIZED_PROVIDER_TEMPLATE,
                        "Draft " + entry.getKey() + " contains unauthorized provider template identifier"));
            }
        }

        String combinedDraftText = texts.values().stream()
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining(" "));

        // 3. Token-exact quantity grounding: percentages and monetary amounts. Both the raw draft and its leetspeak
        // view ("U5D 100" is "USD 100") are checked against the raw and folded context, so the two views cannot differ.
        List<String> draftViews = withLeetViews(combinedDraftText);
        Set<String> contextPercentages = new HashSet<>();
        Set<String> contextAmounts = new HashSet<>();
        // Numbers attached to price terms count as grounded even when the currency marker is omitted
        Set<String> contextNumbers = new HashSet<>();
        // Each context view is scanned on its own so a token or price term cannot straddle the two copies.
        for (String contextView : withLeetViews(normalizedContext)) {
            contextPercentages.addAll(extractTokens(PERCENTAGE_PATTERN, contextView));
            contextAmounts.addAll(extractTokens(AMOUNT_PATTERN, contextView));
            Matcher contextPriceMatcher = PRICE_TERM_NUMBER_PATTERN.matcher(contextView);
            while (contextPriceMatcher.find()) {
                contextNumbers.add(normalizeQuantities(contextPriceMatcher.group(1)));
            }
        }
        for (String amountToken : contextAmounts) {
            Matcher numberMatcher = NUMBER_PATTERN.matcher(amountToken);
            if (numberMatcher.find()) {
                contextNumbers.add(normalizeQuantities(numberMatcher.group()));
            }
        }
        for (String view : draftViews) {
            Matcher draftPctMatcher = PERCENTAGE_PATTERN.matcher(view);
            while (draftPctMatcher.find()) {
                if (!contextPercentages.contains(quantityKey(draftPctMatcher.group()))) {
                    return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_PERCENTAGE,
                            "Draft contains hallucinated percentage or discount amount not grounded in context: '" + draftPctMatcher.group().trim() + "'"));
                }
            }
            Matcher draftAmtMatcher = AMOUNT_PATTERN.matcher(view);
            while (draftAmtMatcher.find()) {
                if (!contextAmounts.contains(quantityKey(draftAmtMatcher.group()))) {
                    return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_PRICE,
                            "Draft contains hallucinated price or monetary amount not grounded in context: '" + draftAmtMatcher.group().trim() + "'"));
                }
            }
            Matcher priceNumberMatcher = PRICE_TERM_NUMBER_PATTERN.matcher(view);
            while (priceNumberMatcher.find()) {
                String number = normalizeQuantities(priceNumberMatcher.group(1));
                if (!contextNumbers.contains(number)) {
                    return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_PRICE,
                            "Draft contains hallucinated price not grounded in context: '" + number + "'"));
                }
            }
        }

        // 4. Offer symbols and terms grounding check
        for (String symbol : OFFER_SYMBOLS) {
            if (combinedDraftText.contains(symbol) && !offerContextLower.contains(symbol)) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_OFFER_SYMBOL,
                        "Offer symbol '" + symbol + "' in draft is not present in context"));
            }
        }

        // Leetspeak spellings ("d3scuento") are only folded for offer vocabulary; amounts keep their digits. The refusal
        // negation is removed from each view after folding, so "No hay d3scuento" is neutralised like "No hay descuento".
        List<String> offerContextViews = withLeetViews(offerContextLower);
        for (String view : draftViews) {
            String candidate = refusalRationale ? NEGATED_OFFER_PATTERN.matcher(view).replaceAll(" ") : view;
            for (Pattern wordPattern : OFFER_WORD_PATTERNS) {
                Matcher draftMatcher = wordPattern.matcher(candidate);
                while (draftMatcher.find()) {
                    String word = draftMatcher.group().toLowerCase(Locale.ROOT);
                    Pattern specificPattern = offerTermBoundary(word);
                    if (offerContextViews.stream().noneMatch(context -> specificPattern.matcher(context).find())) {
                        return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.HALLUCINATED_OFFER_TERM,
                                "Offer term '" + word + "' in draft is not present in context"));
                    }
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
        String contextLower = canonicalize(allowedContextText);
        for (String evidenceItem : evidence) {
            String trimmed = evidenceItem.trim();
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0 || colonIdx >= trimmed.length() - 1) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNGROUNDED_EVIDENCE,
                        "Draft evidence item must be a structured citation with format 'Label: Value': '" + evidenceItem + "'"));
            }
            String label = trimmed.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
            String value = trimmed.substring(colonIdx + 1).trim().toLowerCase(Locale.ROOT);
            String canonicalValue = canonicalize(value);
            DraftGroundingContext.EvidenceSource source = grounding != null ? grounding.sourceForLabel(label) : null;
            if (grounding != null && source == null) {
                return Optional.of(DraftSafetyViolation.of(DraftSafetyViolation.UNGROUNDED_EVIDENCE,
                        "Draft evidence item uses an unsupported label: '" + evidenceItem + "'"));
            }
            boolean grounded = !value.isEmpty() && (source == null
                    ? contextLower.contains(canonicalValue)
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
            tokens.add(quantityKey(matcher.group()));
        }
        return tokens;
    }

    /** Comparison key of a percentage or amount: whitespace-insensitive, so "10 %" equals "10%" and "$ 5" equals "$5". */
    private static String quantityKey(String token) {
        return normalizeQuantities(token).replaceAll("\\s+", "");
    }

    private static String normalizeQuantities(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\s\\u00a0\\u2007\\u202f\\u2009]+", " ").replaceAll("(?<=\\d)[ \\u00a0\\u202f](?=\\d{3})", "").replace(",", ".");
    }

    public static boolean containsUrl(String text) {
        return containsCanonicalUrl(canonicalize(text, false));
    }

    public static boolean containsProviderTemplate(String text) {
        return containsCanonicalProviderTemplate(canonicalize(text));
    }

    private static boolean containsCanonicalUrl(String canonical) {
        if (canonical.isBlank()) {
            return false;
        }
        return URL_PATTERN.matcher(canonical).find() || URL_PATTERN.matcher(refang(canonical)).find();
    }

    private static boolean containsCanonicalProviderTemplate(String canonical) {
        return !canonical.isBlank() && PROVIDER_TEMPLATE_PATTERN.matcher(canonical).find();
    }

    /**
     * Single pre-processing step shared by URL, provider identifier, percentage, amount and offer matching: NFKC folds
     * fullwidth and compatibility forms, format characters (zero-width space and joiners, soft hyphen, bidi marks)
     * are dropped, and Cyrillic/Greek look-alikes are folded to Latin so no vocabulary is matched on a raw spelling.
     */
    private static String canonicalize(String text) {
        return canonicalize(text, true);
    }

    private static String canonicalize(String text, boolean writtenPercentages) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String folded = FORMAT_CHARACTER_PATTERN.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC))
                .replaceAll("").toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); i++) {
            char c = folded.charAt(i);
            out.append(CONFUSABLES.getOrDefault(c, c));
        }
        return writtenPercentages ? PERCENT_WORD_PATTERN.matcher(out).replaceAll("%") : out.toString();
    }

    /** Turns defanged links ("promo[.]test", "hxxp", "[://]") back into their live form for URL detection only. */
    private static String refang(String canonical) {
        String text = IDEOGRAPHIC_DOT_PATTERN.matcher(canonical).replaceAll(".");
        text = DEFANGED_DOT_PATTERN.matcher(text).replaceAll(".");
        text = DEFANGED_COLON_PATTERN.matcher(text).replaceAll("://");
        return DEFANGED_SCHEME_PATTERN.matcher(text).replaceAll("http$1");
    }

    /** The text itself plus its leetspeak views (digits folded everywhere, or kept in a trailing numeric suffix). */
    private static List<String> withLeetViews(String text) {
        List<String> views = new java.util.ArrayList<>(List.of(text));
        for (String folded : List.of(foldLeet(text, false), foldLeet(text, true))) {
            if (!views.contains(folded)) {
                views.add(folded);
            }
        }
        return views;
    }

    /**
     * Grounding boundary of an offer term. Currency codes and words may touch a digit ("JPY100", "100USD"); every
     * other term must not continue into a letter or digit, so "2x1" never grounds on "2x10".
     */
    private static Pattern offerTermBoundary(String word) {
        boolean currency = word.matches("(?:" + CURRENCY_CODES + "|" + CURRENCY_WORDS + ")");
        return currency
                ? Pattern.compile("(?i)(?<!\\p{L})" + Pattern.quote(word) + "(?!\\p{L})")
                : Pattern.compile("(?i)(?<![\\p{L}\\p{N}])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}])");
    }

    /**
     * Folds common digit/at-sign substitutions, but only inside tokens that also contain letters. With
     * {@code keepNumericSuffix} a trailing digit run after the last letter is preserved, so "U5D100" becomes
     * "usd100" and keeps its amount.
     */
    private static String foldLeet(String text, boolean keepNumericSuffix) {
        Matcher tokens = WORD_TOKEN_PATTERN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (tokens.find()) {
            String token = tokens.group();
            boolean hasLetter = token.codePoints().anyMatch(Character::isLetter);
            String folded = token;
            if (hasLetter && !COMPACT_PROMOTION_PATTERN.matcher(token).matches()) {
                Matcher suffix = NUMERIC_SUFFIX_PATTERN.matcher(token);
                folded = keepNumericSuffix && suffix.find()
                        ? leetToken(token.substring(0, suffix.start())) + token.substring(suffix.start())
                        : leetToken(token);
            }
            tokens.appendReplacement(out, Matcher.quoteReplacement(folded));
        }
        tokens.appendTail(out);
        return out.toString();
    }

    private static String leetToken(String token) {
        if (!LEET_DIGIT_PATTERN.matcher(token).find()) {
            return token;
        }
        StringBuilder out = new StringBuilder(token.length());
        for (char c : token.toCharArray()) {
            out.append(switch (c) {
                case '0' -> 'o';
                case '1' -> 'i';
                case '3' -> 'e';
                case '4' -> 'a';
                case '5' -> 's';
                case '7' -> 't';
                case '@' -> 'a';
                default -> c;
            });
        }
        return out.toString();
    }

    private static Map<Character, Character> confusables() {
        String cyrillic = "аеорсхуіјѕԁһӏ";
        String cyrillicLatin = "aeopcxyijsdhl";
        String greek = "οαεινρτκυ";
        String greekLatin = "oaeinptky";
        Map<Character, Character> map = new java.util.HashMap<>();
        for (int i = 0; i < cyrillic.length(); i++) {
            map.put(cyrillic.charAt(i), cyrillicLatin.charAt(i));
        }
        for (int i = 0; i < greek.length(); i++) {
            map.put(greek.charAt(i), greekLatin.charAt(i));
        }
        return Map.copyOf(map);
    }
}
