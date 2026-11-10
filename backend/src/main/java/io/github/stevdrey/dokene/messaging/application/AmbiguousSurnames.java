// ABOUTME: Lets a customer's own full name through the message safety rules when its surname is also an offer word.
// ABOUTME: A closed list, only as the last word of a capitalized person name and never near a quantity (issue 326).
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.DraftSafetyValidator;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Real surnames that the safety vocabulary reads as offer or currency words. This is deliberately not part of
 * {@code DraftSafetyValidator}: its rules are unchanged, and it receives the text with such a name blanked. It fails
 * closed: a name that does not read as a person's, or an occurrence near a quantity or a commercial word, is left for
 * the validator to refuse. The checks are a bound on the exemption, not a proof that a name is genuine; a member still
 * reviews the rendered message before it can be approved.
 */
final class AmbiguousSurnames {
    private static final Set<String> SURNAMES = Set.of("bono", "soles", "reales", "precio", "price", "deal", "rand",
            "sale", "gift");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final String LETTERS = "[\\p{IsLatin}\\p{M}]";
    private static final String WORD = "(?=\\p{IsLatin})\\p{Lu}" + LETTERS + "*(?:['’-]" + LETTERS + "+)*\\.?";
    /** Two to four capitalized Latin words, so a connector or a sentence never reads as a person's name. */
    private static final Pattern PERSON_NAME = Pattern.compile(WORD + "(?: " + WORD + "){1,3}");
    private static final Pattern WORDS = Pattern.compile("\\p{L}+");
    /** The reach of a quantity or commercial word around the name, as the validator reads a price near its term. */
    private static final int REACH = 30;

    /** The validator's own written quantities, so a number it reads cannot be missing here. */
    private static final Set<String> QUANTITY_WORDS = DraftSafetyValidator.writtenQuantityWords().stream()
            .map(AmbiguousSurnames::withoutMarks).collect(Collectors.toUnmodifiableSet());
    /** Ordinary articles the validator also counts: they refuse a name's earlier word but not nearby text. */
    private static final Set<String> ARTICLES = Set.of("un", "una");
    private static final Set<String> COMMERCIAL_WORDS = Set.of("aumento", "alza", "cambio", "ajuste", "baja", "bajo",
            "nuevo", "nueva", "gran", "mejor", "súper", "super", "mega", "especial", "exclusivo", "exclusiva",
            "rebajado", "rebajada", "hoy", "best", "new", "big", "great", "flash", "hot", "low", "lowest", "top",
            "special", "exclusive", "today", "increase", "change", "adjustment");
    private static final Set<String> FUNCTION_WORDS = Set.of("su", "sus", "tu", "tus", "mi", "mis", "el", "la", "los",
            "las", "un", "una", "unos", "unas", "le", "les", "de", "del", "por", "para", "con", "the", "a", "an",
            "your", "our", "my", "their", "his", "her", "of", "for", "with", "no");

    private AmbiguousSurnames() {
    }

    /**
     * {@code text} with each whole occurrence of {@code displayName} blanked at its surname, when the name qualifies
     * and no quantity or commercial word stands within reach of that occurrence.
     */
    static String neutralized(String text, String displayName) {
        if (!qualifies(displayName)) {
            return text;
        }
        String surname = displayName.substring(displayName.lastIndexOf(' ') + 1);
        String blanked = displayName.substring(0, displayName.length() - surname.length())
                + "x".repeat(surname.length());
        Matcher occurrences = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(displayName) + "(?![\\p{L}\\p{N}])")
                .matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (occurrences.find()) {
            String before = text.substring(Math.max(0, occurrences.start() - REACH), occurrences.start());
            String after = text.substring(occurrences.end(), Math.min(text.length(), occurrences.end() + REACH));
            occurrences.appendReplacement(out,
                    Matcher.quoteReplacement(inReach(before) || inReach(after) ? occurrences.group() : blanked));
        }
        occurrences.appendTail(out);
        return out.toString();
    }

    private static boolean qualifies(String displayName) {
        if (!Normalizer.isNormalized(displayName, Normalizer.Form.NFKC)
                || !PERSON_NAME.matcher(displayName).matches()) {
            return false;
        }
        String[] words = displayName.split(" ");
        return SURNAMES.contains(String.join("", parts(words[words.length - 1])))
                && Arrays.stream(words, 0, words.length - 1).allMatch(AmbiguousSurnames::mayPrecedeSurname);
    }

    private static boolean mayPrecedeSurname(String word) {
        return parts(word).stream().noneMatch(part -> SURNAMES.contains(part) || QUANTITY_WORDS.contains(part)
                || COMMERCIAL_WORDS.contains(part) || FUNCTION_WORDS.contains(part));
    }

    /** Whether {@code window} holds a number in any form the validator folds, or a word that makes the name a claim. */
    private static boolean inReach(String window) {
        String folded = folded(window);
        if (folded.codePoints().anyMatch(codePoint -> Character.isDigit(codePoint)
                || Character.getType(codePoint) == Character.OTHER_NUMBER
                || Character.getType(codePoint) == Character.LETTER_NUMBER)) {
            return true;
        }
        return parts(folded).stream().anyMatch(word -> COMMERCIAL_WORDS.contains(word)
                || QUANTITY_WORDS.contains(word) && !ARTICLES.contains(word));
    }

    private static List<String> parts(String text) {
        return WORDS.matcher(folded(text)).results().map(MatchResult::group).toList();
    }

    /** The text as the validator reads it, without accents, so a marked spelling cannot stand apart from its word. */
    private static String folded(String text) {
        return withoutMarks(DraftSafetyValidator.canonicalized(text));
    }

    private static String withoutMarks(String text) {
        return MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
    }
}
