// ABOUTME: The locales a message body may be submitted in.
// ABOUTME: Phase 3 supports Latin American Spanish only.
package io.github.stevdrey.dokene.messaging.domain;

import java.util.Set;

public final class MessagingLocales {
    public static final Set<String> SUPPORTED = Set.of("es-419");

    private MessagingLocales() {
    }

    public static boolean supported(String locale) {
        return locale != null && SUPPORTED.contains(locale);
    }
}
