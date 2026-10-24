// ABOUTME: The locales a message body may be submitted in, and the Meta template languages each one accepts.
// ABOUTME: Latin American Spanish is the default; US English is the one explicit addition (issue #268).
package io.github.stevdrey.dokene.messaging.domain;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MessagingLocales {
    /** Latin American Spanish, the default locale of every manual and AI-drafted message. */
    public static final String DEFAULT = "es-419";
    /** US English. Manual messages only: AI drafts have no validated English prompt or safety vocabulary. */
    public static final String ENGLISH_US = "en-US";
    /** Meta's code for US English, the only template language an {@link #ENGLISH_US} message accepts. */
    public static final String META_ENGLISH_US = "en_US";

    public static final Set<String> SUPPORTED = Set.of(DEFAULT, ENGLISH_US);

    private MessagingLocales() {
    }

    public static boolean supported(String locale) {
        return locale != null && SUPPORTED.contains(locale);
    }

    /**
     * The locales this deployment accepts a new message in, default first: {@link #ENGLISH_US} only once the English
     * deployment switch is on (issue 268, expand first). Submit refuses any other locale, and the client offers exactly
     * these, so the two cannot disagree.
     */
    public static List<String> accepted(boolean englishEnabled) {
        return englishEnabled ? List.of(DEFAULT, ENGLISH_US) : List.of(DEFAULT);
    }

    /**
     * Whether a template written in the Meta language code may carry a message in this locale (ADR 0025, §1).
     * {@code es-419} has no Meta code of its own, so it keeps accepting any Spanish template ({@code es},
     * {@code es_MX}, ...). {@code en-US} accepts exactly {@code en_US}: another English variant is not what the
     * operator reviewed. Any other locale accepts nothing.
     */
    public static boolean acceptsTemplateLanguage(String locale, String templateLanguage) {
        if (locale == null || templateLanguage == null) {
            return false;
        }
        return switch (locale) {
            case DEFAULT -> primarySubtag(templateLanguage).equals("es");
            case ENGLISH_US -> templateLanguage.equals(META_ENGLISH_US);
            default -> false;
        };
    }

    private static String primarySubtag(String metaLanguage) {
        int index = metaLanguage.indexOf('_');
        return (index < 0 ? metaLanguage : metaLanguage.substring(0, index)).toLowerCase(Locale.ROOT);
    }
}
