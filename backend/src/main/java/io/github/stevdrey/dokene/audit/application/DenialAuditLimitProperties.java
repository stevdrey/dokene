// ABOUTME: Limits of the authorization denial audit bound (spec 0020, ADR 0032): rows per key and per identity in one
// ABOUTME: fixed window, tracker capacity and sweep interval. Out of range values stop the application at startup.
package io.github.stevdrey.dokene.audit.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("dokene.audit.denial-limit")
public record DenialAuditLimitProperties(
        @DefaultValue("10") int rowsPerKey,
        @DefaultValue("60") int rowsPerIdentity,
        @DefaultValue("PT15M") Duration window,
        @DefaultValue("50000") int maxTrackedKeys,
        @DefaultValue("PT1M") Duration sweepInterval
) {
    private static final Duration SHORTEST_WINDOW = Duration.ofMinutes(1);
    private static final Duration LONGEST_WINDOW = Duration.ofHours(24);
    private static final Duration SHORTEST_SWEEP = Duration.ofSeconds(5);

    public DenialAuditLimitProperties {
        Objects.requireNonNull(window, "dokene.audit.denial-limit.window is required");
        Objects.requireNonNull(sweepInterval, "dokene.audit.denial-limit.sweep-interval is required");
        if (rowsPerKey < 1 || rowsPerKey > 10_000) {
            throw new IllegalArgumentException("dokene.audit.denial-limit.rows-per-key must be 1 to 10000");
        }
        if (rowsPerIdentity < rowsPerKey || rowsPerIdentity > 100_000) {
            throw new IllegalArgumentException(
                    "dokene.audit.denial-limit.rows-per-identity must be rows-per-key to 100000");
        }
        if (window.compareTo(SHORTEST_WINDOW) < 0 || window.compareTo(LONGEST_WINDOW) > 0) {
            throw new IllegalArgumentException("dokene.audit.denial-limit.window must be PT1M to PT24H");
        }
        if (maxTrackedKeys < 1_000 || maxTrackedKeys > 1_000_000) {
            throw new IllegalArgumentException("dokene.audit.denial-limit.max-tracked-keys must be 1000 to 1000000");
        }
        if (sweepInterval.compareTo(SHORTEST_SWEEP) < 0 || sweepInterval.compareTo(window) > 0) {
            throw new IllegalArgumentException(
                    "dokene.audit.denial-limit.sweep-interval must be PT5S to the value of window");
        }
    }
}
