// ABOUTME: The origin Keycloak sends the browser back to (DOKENE_PUBLIC_ORIGIN, spec 0019, issue 318), validated in
// ABOUTME: its one accepted form; the login callback and logout return address are built from it alone.
package io.github.stevdrey.dokene.identity.security;

import io.github.stevdrey.dokene.http.LoopbackHosts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * {@code https://<host>[:<port>]}, or {@code http://} on a loopback host, with nothing else: no path, no trailing
 * slash, no user info, lower case, no default port written out. The value is used exactly as written and never
 * normalized, so it must already be the string the identity provider has registered. For the same reason a host
 * must be spelled the way a browser writes it: an IPv4 address in full dotted form, an IPv6 address in its RFC 5952
 * canonical form. A refusal never repeats the value, because a refused value may carry user info.
 */
public record PublicOrigin(String value) {

    static final String SETTING = "DOKENE_PUBLIC_ORIGIN (dokene.security.public-origin)";
    static final String CALLBACK_PATH = "/login/oauth2/code/";

    private static final Pattern DNS_HOST =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern IPV6_GROUP = Pattern.compile("[0-9a-f]{1,4}");
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern IPV4_TAIL = Pattern.compile(IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}");
    private static final Pattern NUMERIC_LABEL = Pattern.compile("[0-9]+|0x[0-9a-f]*");
    private static final int MAX_HOST_LENGTH = 253;
    private static final Pattern PORT = Pattern.compile("[1-9][0-9]{0,4}");

    public PublicOrigin {
        validate(value);
    }

    public boolean isHttps() {
        return value.startsWith("https://");
    }

    /** The redirect URI template of a client registration; Spring fills in the registration id, never the request. */
    public String loginCallbackTemplate() {
        return value + CALLBACK_PATH + "{registrationId}";
    }

    public String loginCallback(String registrationId) {
        return value + CALLBACK_PATH + registrationId;
    }

    /** The {@code post_logout_redirect_uri} sent to the provider's end session endpoint. */
    public String logoutReturn() {
        return value + "/";
    }

    private static void validate(String value) {
        if (value == null || value.isBlank()) {
            throw refused("must be set");
        }
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw refused("must not contain whitespace");
        }
        int separator = value.indexOf("://");
        if (separator <= 0) {
            throw refused("must start with a scheme: https://, or http:// on localhost, 127.0.0.1 or [::1]");
        }
        String scheme = value.substring(0, separator);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw refused(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http")
                    ? "scheme must be lower case"
                    : "scheme must be https, or http on localhost, 127.0.0.1 or [::1]");
        }
        String authority = value.substring(separator + 3);
        if (authority.chars().anyMatch(character -> character == '/' || character == '?' || character == '#')) {
            throw refused("must not end with a slash or carry a path, query or fragment");
        }
        if (authority.indexOf('@') >= 0) {
            throw refused("must not contain user info");
        }
        HostAndPort hostAndPort = HostAndPort.split(authority);
        String host = hostAndPort.host();
        if (!host.chars().allMatch(character -> character < 0x80)) {
            throw refused("host must be ASCII; write an internationalized name in its punycode (xn--) form");
        }
        if (!host.equals(host.toLowerCase(Locale.ROOT))) {
            throw refused("host must be lower case");
        }
        if (!DNS_HOST.matcher(host).matches() && !isBracketedIpv6(host)) {
            throw refused("host is not a valid host name or address");
        }
        // A browser rewrites every other spelling of an IPv6 address ([2001:db8:0:0:0:0:0:1] becomes [2001:db8::1]),
        // so the value would not be the origin it is sent to.
        if (host.startsWith("[") && !host.equals(canonicalIpv6(host))) {
            throw refused("IPv6 address must be in its canonical form: no leading zeros in a group, the longest run "
                    + "of two or more zero groups written as ::, and no dotted IPv4 tail");
        }
        if (host.length() > MAX_HOST_LENGTH) {
            throw refused("host must be at most 253 characters");
        }
        // A browser reads a host whose last label is a number as an IPv4 address and rewrites or rejects every
        // other spelling of it (127.1 becomes 127.0.0.1), so the value would not be the one it is sent to.
        if (NUMERIC_LABEL.matcher(host.substring(host.lastIndexOf('.') + 1)).matches()
                && !IPV4_TAIL.matcher(host).matches()) {
            throw refused("host ends in a number, so it must be a full dotted IPv4 address such as 192.0.2.10");
        }
        String port = hostAndPort.port();
        if (port != null) {
            if (!PORT.matcher(port).matches() || Integer.parseInt(port) > 65535) {
                throw refused("port must be a number from 1 to 65535 without leading zeros");
            }
            if ((scheme.equals("https") && port.equals("443")) || (scheme.equals("http") && port.equals("80"))) {
                throw refused("must not write out the default port (:443 for https, :80 for http)");
            }
        }
        // The exact rule on purpose: the value is sent as written, so only the spelling a browser writes (issue 428).
        if (scheme.equals("http") && !LoopbackHosts.isExactSpelling(host)) {
            throw refused("allows plain http only on localhost, 127.0.0.1 or [::1]; use https for any other host");
        }
    }

    /**
     * Eight groups of one to four hex digits, or fewer with a single {@code ::}; the last two groups may be written
     * as a dotted IPv4 address. No zone id, because an origin a browser is sent to cannot carry one.
     */
    private static boolean isBracketedIpv6(String host) {
        if (host.length() < 2 || !host.startsWith("[") || !host.endsWith("]")) {
            return false;
        }
        String address = host.substring(1, host.length() - 1);
        int compression = address.indexOf("::");
        if (compression != address.lastIndexOf("::")) {
            return false;
        }
        if (compression < 0) {
            return countIpv6Groups(address, true) == 8;
        }
        int before = countIpv6Groups(address.substring(0, compression), false);
        int after = countIpv6Groups(address.substring(compression + 2), true);
        return before >= 0 && after >= 0 && before + after <= 7;
    }

    /** The number of 16 bit groups in a colon separated run, or -1 when it is malformed; an empty run has none. */
    private static int countIpv6Groups(String run, boolean ipv4TailAllowed) {
        if (run.isEmpty()) {
            return 0;
        }
        String[] parts = run.split(":", -1);
        int groups = 0;
        for (int index = 0; index < parts.length; index++) {
            boolean last = index == parts.length - 1;
            if (last && ipv4TailAllowed && IPV4_TAIL.matcher(parts[index]).matches()) {
                groups += 2;
            } else if (IPV6_GROUP.matcher(parts[index]).matches()) {
                groups++;
            } else {
                return -1;
            }
        }
        return groups;
    }

    /** The RFC 5952 spelling of an address {@link #isBracketedIpv6} accepted, brackets included. */
    private static String canonicalIpv6(String host) {
        String address = host.substring(1, host.length() - 1);
        int compression = address.indexOf("::");
        List<Integer> groups = ipv6Groups(compression < 0 ? address : address.substring(0, compression));
        if (compression >= 0) {
            List<Integer> tail = ipv6Groups(address.substring(compression + 2));
            while (groups.size() + tail.size() < 8) {
                groups.add(0);
            }
            groups.addAll(tail);
        }
        int runStart = -1;
        int runLength = 1;
        for (int start = 0; start < groups.size(); start++) {
            int end = start;
            while (end < groups.size() && groups.get(end) == 0) {
                end++;
            }
            if (end - start > runLength) {
                runStart = start;
                runLength = end - start;
            }
            start = Math.max(start, end - 1);
        }
        StringBuilder canonical = new StringBuilder("[");
        for (int index = 0; index < groups.size(); index++) {
            if (index == runStart) {
                canonical.append("::");
                index += runLength - 1;
                continue;
            }
            if (index > 0 && canonical.charAt(canonical.length() - 1) != ':') {
                canonical.append(':');
            }
            canonical.append(Integer.toHexString(groups.get(index)));
        }
        return canonical.append(']').toString();
    }

    /** The 16 bit groups of a colon separated run; a dotted IPv4 part counts as two. */
    private static List<Integer> ipv6Groups(String run) {
        List<Integer> groups = new ArrayList<>();
        if (run.isEmpty()) {
            return groups;
        }
        for (String part : run.split(":", -1)) {
            if (part.indexOf('.') < 0) {
                groups.add(Integer.parseInt(part, 16));
                continue;
            }
            String[] octets = part.split("\\.");
            groups.add(Integer.parseInt(octets[0]) << 8 | Integer.parseInt(octets[1]));
            groups.add(Integer.parseInt(octets[2]) << 8 | Integer.parseInt(octets[3]));
        }
        return groups;
    }

    private static IllegalStateException refused(String rule) {
        return new IllegalStateException(SETTING + " " + rule);
    }

    /** {@code port} is null when none is written, and empty when only the colon is. */
    private record HostAndPort(String host, String port) {

        static HostAndPort split(String authority) {
            if (authority.startsWith("[")) {
                int close = authority.indexOf(']');
                if (close < 0) {
                    throw refused("host is not a valid host name or address");
                }
                String rest = authority.substring(close + 1);
                if (!rest.isEmpty() && !rest.startsWith(":")) {
                    throw refused("host is not a valid host name or address");
                }
                return new HostAndPort(authority.substring(0, close + 1), rest.isEmpty() ? null : rest.substring(1));
            }
            int colon = authority.indexOf(':');
            if (colon < 0) {
                return new HostAndPort(authority, null);
            }
            if (authority.indexOf(':', colon + 1) >= 0) {
                throw refused("host is not a valid host name or address; put an IPv6 address in brackets");
            }
            return new HostAndPort(authority.substring(0, colon), authority.substring(colon + 1));
        }
    }
}
