// ABOUTME: Holds the configured DOKENE_PUBLIC_ORIGIN (spec 0019, issue 318): required with a client registration,
// ABOUTME: logged once at startup, and the only source of the HSTS decision on every security filter chain.
package io.github.stevdrey.dokene.identity.security;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

/**
 * A set origin is validated whether or not a client registration exists, because its scheme decides HSTS on every
 * response. Without a registration nothing is sent back to a provider, so the origin may be unset, and then no
 * response claims https.
 */
public final class PublicOriginSettings {

    static final long HSTS_MAX_AGE_SECONDS = 31_536_000L;

    private static final Logger log = LoggerFactory.getLogger(PublicOriginSettings.class);

    private final PublicOrigin origin;

    PublicOriginSettings(String configured, boolean clientRegistrationConfigured) {
        boolean unset = configured == null || configured.isBlank();
        if (unset && clientRegistrationConfigured) {
            throw new IllegalStateException(
                    PublicOrigin.SETTING + " must be set when an OAuth client registration is configured");
        }
        this.origin = unset ? null : new PublicOrigin(configured);
        log.info("OAuth public origin {}; HSTS {}", origin == null ? "not set" : origin.value(),
                hstsEnabled() ? "on" : "off");
    }

    public Optional<PublicOrigin> origin() {
        return Optional.ofNullable(origin);
    }

    public boolean hstsEnabled() {
        return origin != null && origin.isHttps();
    }

    /**
     * HSTS from the origin's scheme, never from the request's: Spring's default writes it only for a request it
     * believes is secure, which under {@code framework} a forged {@code X-Forwarded-Proto} decides.
     */
    public void applyHsts(HeadersConfigurer<HttpSecurity> headers) {
        if (hstsEnabled()) {
            headers.httpStrictTransportSecurity(hsts -> hsts
                    .requestMatcher(AnyRequestMatcher.INSTANCE)
                    .maxAgeInSeconds(HSTS_MAX_AGE_SECONDS)
                    .includeSubDomains(false));
        } else {
            headers.httpStrictTransportSecurity(hsts -> hsts.disable());
        }
    }
}
