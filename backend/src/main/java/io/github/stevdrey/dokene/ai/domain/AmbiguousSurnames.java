// ABOUTME: Lets a customer's own full name through the message safety rules when its surname is also an offer word.
// ABOUTME: A closed list, only as the last word of a capitalized person name and never near a quantity (issue 326).
package io.github.stevdrey.dokene.ai.domain;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
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
public final class AmbiguousSurnames {
    private static final Set<String> SURNAMES = Set.of("bono", "soles", "reales", "precio", "price", "deal", "rand",
            "sale", "gift");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final String LETTERS = "[\\p{IsLatin}\\p{M}]";
    private static final String WORD = "(?=\\p{IsLatin})\\p{Lu}" + LETTERS + "*(?:['’-]" + LETTERS + "+)*\\.?";
    /** Two to four capitalized Latin words, so a connector or a sentence never reads as a person's name. */
    private static final Pattern PERSON_NAME = Pattern.compile(WORD + "(?: " + WORD + "){1,3}");
    private static final Pattern WORDS = Pattern.compile("\\p{L}+");
    private static final Pattern SPACING = Pattern.compile("[\\s\\p{Z}\\u2800]+");
    /** Longer than any written quantity: such a run counts as one word, so it cannot push a number out of reach. */
    private static final Pattern LONG_WORD = Pattern.compile("\\p{L}{21,}");
    /** The reach of a quantity or commercial word around the name, as the validator reads a price near its term. */
    private static final int REACH = 30;

    /** The validator's own written quantities, so a number it reads cannot be missing here. */
    private static final Set<String> QUANTITY_WORDS = DraftSafetyValidator.writtenQuantityWords().stream()
            .map(AmbiguousSurnames::withoutMarks).collect(Collectors.toUnmodifiableSet());
    /** Ordinary articles the validator also counts: they refuse a name's earlier word but not nearby text. */
    private static final Set<String> ARTICLES = Set.of("un", "una");
    private static final Set<String> COMMERCIAL_WORDS = Set.of("aumento", "alza", "cambio", "ajuste", "baja", "bajo",
            "nuevo", "nueva", "gran", "mejor", "super", "mega", "especial", "exclusivo", "exclusiva",
            "rebajado", "rebajada", "hoy", "best", "new", "big", "great", "flash", "hot", "low", "lowest", "top",
            "special", "exclusive", "today", "increase", "change", "adjustment");
    /** The commercial words that turn the name into a claim when they stand near it; ordinary ones like "hoy" do not. */
    private static final Set<String> CLAIM_WORDS = Set.of("aumento", "alza", "ajuste", "cambio", "rebajado", "rebajada",
            "especial", "exclusivo", "exclusiva", "mejor", "best", "special", "exclusive", "increase", "change",
            "adjustment");
    private static final Set<String> FUNCTION_WORDS = Set.of("su", "sus", "tu", "tus", "mi", "mis", "el", "la", "los",
            "las", "un", "una", "unos", "unas", "le", "les", "de", "del", "por", "para", "con", "the", "a", "an",
            "your", "our", "my", "their", "his", "her", "of", "for", "with", "no");

    private AmbiguousSurnames() {
    }

    /**
     * {@code text} with each whole occurrence of {@code displayName} blanked at its surname, when the name qualifies
     * and no number, written quantity or claim word stands within reach.
     */
    public static String neutralized(String text, String displayName) {
        if (!qualifies(displayName)) {
            return text;
        }
        String surname = displayName.substring(displayName.lastIndexOf(' ') + 1);
        // Only the letters are blanked: a final period stays, so it can still form a link with what follows.
        String blanked = displayName.substring(0, displayName.length() - surname.length())
                + surname.replaceAll("[^.]", "x");
        Matcher occurrences = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(displayName) + "(?![\\p{L}\\p{N}])")
                .matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (occurrences.find()) {
            String before = readable(text.substring(0, occurrences.start()));
            String after = readable(text.substring(occurrences.end()));
            before = before.substring(Math.max(0, before.length() - REACH));
            after = after.substring(0, Math.min(after.length(), REACH));
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

    /** Whether a {@link #readable} window holds a number in any form, or a word that makes the name a claim. */
    private static boolean inReach(String window) {
        if (window.codePoints().anyMatch(codePoint -> Character.isDigit(codePoint)
                || Character.getType(codePoint) == Character.OTHER_NUMBER
                || Character.getType(codePoint) == Character.LETTER_NUMBER)) {
            return true;
        }
        return WORDS.matcher(window).results().map(MatchResult::group).anyMatch(word -> CLAIM_WORDS.contains(word)
                || QUANTITY_WORDS.contains(word) && !ARTICLES.contains(word));
    }

    /** The text as the validator reads it with spacing collapsed, so padding cannot move a number away. */
    private static String readable(String text) {
        return SPACING.matcher(LONG_WORD.matcher(folded(text)).replaceAll("x")).replaceAll(" ");
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
