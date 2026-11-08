// ABOUTME: Shared grammar for operator-listed trusted outbound hosts (Graph API and OpenAI endpoint allowlists): exact
// ABOUTME: lower-case DNS host names only, never a scheme, port, path, wildcard, trailing dot or IP literal (issue 322).
package io.github.stevdrey.dokene.http;

import java.util.regex.Pattern;

public final class TrustedHostNames {

    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$");
    /** A last label of digits only is an IPv4 literal (or a numeric form of one), never a host name. */
    private static final Pattern NUMERIC_LAST_LABEL = Pattern.compile("(^|\\.)[0-9]+$");

    private TrustedHostNames() {
    }

    /** True when {@code entry} is an exact lower-case DNS host name that may be listed as a trusted host. */
    public static boolean isExactHostName(String entry) {
        return entry != null && HOSTNAME.matcher(entry).matches() && !NUMERIC_LAST_LABEL.matcher(entry).find();
    }
}
