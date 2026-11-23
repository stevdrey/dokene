// ABOUTME: The one place the backend decides whether a host is loopback (issue 428). Two named rules: the exact
// ABOUTME: spelling a browser writes, and any spelling that reaches loopback; each caller picks one on purpose.
package io.github.stevdrey.dokene.http;

import java.net.InetAddress;
import java.util.Locale;
import java.util.Set;

/**
 * Loopback host rules, decided from the spelling alone and never by a DNS lookup.
 *
 * <ul>
 *   <li>{@link #isExactSpelling(String)}: exactly {@code localhost}, {@code 127.0.0.1} or {@code [::1]}, compared
 *       as written (callers pass the lower-case host). The public origin uses it because the value is sent to the
 *       browser as written, and both provider endpoint policies use it because they are allow-lists for credentials
 *       and customer data (security invariant 29): a name under {@code localhost} reaches loopback only when the
 *       resolver honours RFC 6761, and another spelling of an address is not what a reviewer reads.</li>
 *   <li>{@link #reachesLoopback(String)}: {@code localhost} and any name under it, with or without the root dot,
 *       and every address literal {@link InetAddress#ofLiteral(String)} reads as loopback: IPv4 in decimal, dotted
 *       or shortened ({@code 127.0.0.2}, {@code 127.1}, {@code 2130706433}), and IPv6 in any form, bracketed or not.
 *       Octal or hex spellings are not recognized, and the JDK never connects to loopback through them: it reads
 *       {@code 0177.0.0.1} as the decimal 177.0.0.1 and refuses {@code 0x7f.0.0.1}. It only says whether a host may
 *       be loopback, so it suits a check that narrows a value, such as the back-channel replay port check, and must
 *       never widen an allow-list.</li>
 * </ul>
 *
 * A name that only a hosts file maps to loopback is recognized by neither rule.
 */
public final class LoopbackHosts {

    private static final Set<String> EXACT_SPELLINGS = Set.of("localhost", "127.0.0.1", "[::1]");
    /** The exact spellings as refusal messages list them, so a message never names a host the rule refuses. */
    public static final String EXACT_SPELLINGS_TEXT = "localhost, 127.0.0.1 or [::1]";

    private LoopbackHosts() {
    }

    /** True when {@code host} is exactly {@code localhost}, {@code 127.0.0.1} or {@code [::1]}. */
    public static boolean isExactSpelling(String host) {
        return host != null && EXACT_SPELLINGS.contains(host);
    }

    /**
     * True when {@code host}, in any case and with or without brackets or the root dot, is a loopback spelling. A name
     * under {@code localhost} is reserved for loopback (RFC 6761), but whether it resolves there is the resolver's
     * choice, so this rule may narrow a value and must never serve as an allow-list.
     */
    public static boolean reachesLoopback(String host) {
        if (host == null) {
            return false;
        }
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.equals("localhost") || name.endsWith(".localhost")) {
            return true;
        }
        boolean bracketed = name.startsWith("[") && name.endsWith("]");
        try {
            return InetAddress.ofLiteral(bracketed ? name.substring(1, name.length() - 1) : name)
                    .isLoopbackAddress();
        } catch (IllegalArgumentException notALiteralAddress) {
            return false;
        }
    }
}
