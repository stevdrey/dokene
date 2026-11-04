// ABOUTME: BFF session revocation settings under dokene.security.session (issue 307, ADR 0007).
// ABOUTME: Validated at startup; together they bound how long a session outlives the identity provider.
package io.github.stevdrey.dokene.identity.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code maxLifetime} ends every session that long after login, however active. Every {@code revalidationInterval} the
 * session's refresh token is redeemed at the provider, so a disabled user or an ended provider session is denied within
 * that interval. While the provider cannot be reached the session survives until {@code revalidationGrace} has passed
 * since the last successful revalidation. {@code revalidationTimeout} bounds each connection and response wait of a
 * revalidation, which runs while the session's other requests wait for it.
 */
@ConfigurationProperties("dokene.security.session")
public record BffSessionProperties(Duration maxLifetime, Duration revalidationInterval, Duration revalidationGrace,
        Duration revalidationTimeout) {

    public BffSessionProperties {
        maxLifetime = maxLifetime == null ? Duration.ofHours(8) : maxLifetime;
        revalidationInterval = revalidationInterval == null ? Duration.ofMinutes(5) : revalidationInterval;
        revalidationGrace = revalidationGrace == null ? Duration.ofMinutes(15) : revalidationGrace;
        revalidationTimeout = revalidationTimeout == null ? Duration.ofSeconds(5) : revalidationTimeout;
        if (!maxLifetime.isPositive()) {
            throw new IllegalStateException("dokene.security.session.max-lifetime must be positive");
        }
        if (!revalidationInterval.isPositive()) {
            throw new IllegalStateException("dokene.security.session.revalidation-interval must be positive");
        }
        if (revalidationGrace.compareTo(revalidationInterval) <= 0) {
            throw new IllegalStateException(
                    "dokene.security.session.revalidation-grace must be longer than revalidation-interval");
        }
        if (!revalidationTimeout.isPositive() || revalidationTimeout.compareTo(revalidationInterval) >= 0) {
            throw new IllegalStateException(
                    "dokene.security.session.revalidation-timeout must be positive and shorter than revalidation-interval");
        }
        if (revalidationGrace.compareTo(maxLifetime) > 0) {
            throw new IllegalStateException(
                    "dokene.security.session.revalidation-grace must not exceed max-lifetime");
        }
    }
}
