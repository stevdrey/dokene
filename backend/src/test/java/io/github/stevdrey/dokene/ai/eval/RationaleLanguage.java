package io.github.stevdrey.dokene.ai.eval;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Words shared by both languages ("no", "a") are deliberately neutral, so a short English refusal such as
 * "No trusted context provided" is not classified as Spanish.
 * Informational operator-language heuristic (issue #126): operator-visible AI text must be Latin American Spanish.
 * It only counts common function words, so it is a hint for reviewers, never a pass/fail invariant or a score.
 */
final class RationaleLanguage {
    private static final Pattern WORD = Pattern.compile("[\\p{L}]+");
    private static final Set<String> SPANISH = Set.of("el", "la", "los", "las", "de", "del", "que", "y", "en", "por",
            "para", "con", "una", "un", "se", "su", "sus", "es", "sin", "al", "ha", "han", "desde", "como", "más",
            "cliente", "compra", "seguimiento", "datos", "faltan", "falta", "historial", "insuficiente", "revisión",
            "señal");
    private static final Set<String> ENGLISH = Set.of("the", "and", "is", "was", "are", "were", "their", "for", "with",
            "of", "to", "an", "that", "this", "has", "have", "from", "by", "on", "in", "customer", "purchase", "ago",
            "trusted", "context", "provided", "allowed", "actions", "insufficient", "history", "only", "not", "missing");

    private RationaleLanguage() {
    }

    static boolean looksSpanish(String text) {
        if (text == null) {
            return false;
        }
        int spanish = 0;
        int english = 0;
        var matcher = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (SPANISH.contains(word)) {
                spanish++;
            }
            if (ENGLISH.contains(word)) {
                english++;
            }
        }
        return spanish > english;
    }
}
