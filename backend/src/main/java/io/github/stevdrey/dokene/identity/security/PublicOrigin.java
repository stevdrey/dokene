// ABOUTME: The origin Keycloak sends the browser back to (DOKENE_PUBLIC_ORIGIN, spec 0019, issue 318), validated in
// ABOUTME: its one accepted form; the login callback and logout return address are built from it alone.
package io.github.stevdrey.dokene.identity.security;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code https://<host>[:<port>]}, or {@code http://} on a loopback host, with nothing else: no path, no trailing
 * slash, no user info, lower case, no default port written out. The value is used exactly as written and never
 * normalized, so it must already be the string the identity provider has registered. A refusal never repeats the
 * value, because a refused value may carry user info.
 */
public record PublicOrigin(String value) {

    static final String SETTING = "DOKENE_PUBLIC_ORIGIN (dokene.security.public-origin)";
    static final String CALLBACK_PATH = "/login/oauth2/code/";

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final Pattern DNS_HOST =
            Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*");
    private static final Pattern IPV6_GROUP = Pattern.compile("[0-9a-f]{1,4}");
    private static final String IPV4_OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])";
    private static final Pattern IPV4_TAIL = Pattern.compile(IPV4_OCTET + "(?:\\." + IPV4_OCTET + "){3}");
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
        String port = hostAndPort.port();
        if (port != null) {
            if (!PORT.matcher(port).matches() || Integer.parseInt(port) > 65535) {
                throw refused("port must be a number from 1 to 65535 without leading zeros");
            }
            if ((scheme.equals("https") && port.equals("443")) || (scheme.equals("http") && port.equals("80"))) {
                throw refused("must not write out the default port (:443 for https, :80 for http)");
            }
        }
        if (scheme.equals("http") && !LOOPBACK_HOSTS.contains(host)) {
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
