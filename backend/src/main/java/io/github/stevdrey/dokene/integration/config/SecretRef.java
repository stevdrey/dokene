// ABOUTME: A reference to where a tenant's WhatsApp token lives, never the token itself (spec 0005 AC-7).
// ABOUTME: Grammar env:DOKENE_WA_<SUFFIX>: the fixed prefix keeps every other variable of the process out of reach.
package io.github.stevdrey.dokene.integration.config;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SecretRef(String scheme, String name) {
    public static final String ENV_SCHEME = "env";
    private static final Pattern GRAMMAR = Pattern.compile("^env:(DOKENE_WA_[A-Z0-9_]{1,100})$");

    public SecretRef {
        Objects.requireNonNull(scheme, "Scheme is required");
        Objects.requireNonNull(name, "Name is required");
        if (!GRAMMAR.matcher(scheme + ":" + name).matches()) {
            throw new IllegalArgumentException("Secret reference does not match the grammar");
        }
    }

    /** The only way in from input; anything outside the grammar (another scheme, case, length) is refused. */
    public static SecretRef parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Secret reference is required");
        }
        Matcher matcher = GRAMMAR.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Secret reference does not match the grammar");
        }
        return new SecretRef(ENV_SCHEME, matcher.group(1));
    }

    /** The stored and displayed form, {@code env:DOKENE_WA_...}. */
    public String value() {
        return scheme + ":" + name;
    }
}
