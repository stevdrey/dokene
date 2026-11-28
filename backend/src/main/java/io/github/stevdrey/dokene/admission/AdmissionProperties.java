// ABOUTME: Binds dokene.admission (ADR 0040) with its defaults and validates it at startup: a value outside its
// ABOUTME: bounds, or a tenant day limit below the tenant minute limit, stops the application.
package io.github.stevdrey.dokene.admission;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Read once at startup, so a change needs a restart of every instance. */
@ConfigurationProperties(prefix = "dokene.admission")
public record AdmissionProperties(Send send, Candidates candidates, Duration lockTimeout, Integer pruneBatch) {
    public static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(1);
    public static final int DEFAULT_PRUNE_BATCH = 500;

    public AdmissionProperties {
        send = send == null ? new Send(null, null, null, null, null) : send;
        candidates = candidates == null ? new Candidates(null, null) : candidates;
        lockTimeout = lockTimeout == null ? DEFAULT_LOCK_TIMEOUT : lockTimeout;
        pruneBatch = pruneBatch == null ? DEFAULT_PRUNE_BATCH : pruneBatch;
        if (lockTimeout.compareTo(Duration.ofMillis(100)) < 0 || lockTimeout.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("dokene.admission.lock-timeout must be between 100ms and 5s");
        }
        requireBetween(pruneBatch, 1, 5000, "dokene.admission.prune-batch");
    }

    public record Send(Integer tenantPerMinute, Integer tenantPerDay, Integer globalPerMinute,
            Integer tenantSharePercent, Duration throttlePause) {
        public static final int DEFAULT_TENANT_PER_MINUTE = 30;
        public static final int DEFAULT_TENANT_PER_DAY = 500;
        public static final int DEFAULT_GLOBAL_PER_MINUTE = 120;
        public static final int DEFAULT_TENANT_SHARE_PERCENT = 25;
        public static final Duration DEFAULT_THROTTLE_PAUSE = Duration.ofSeconds(60);

        public Send {
            tenantPerMinute = tenantPerMinute == null ? DEFAULT_TENANT_PER_MINUTE : tenantPerMinute;
            tenantPerDay = tenantPerDay == null ? DEFAULT_TENANT_PER_DAY : tenantPerDay;
            globalPerMinute = globalPerMinute == null ? DEFAULT_GLOBAL_PER_MINUTE : globalPerMinute;
            tenantSharePercent = tenantSharePercent == null ? DEFAULT_TENANT_SHARE_PERCENT : tenantSharePercent;
            throttlePause = throttlePause == null ? DEFAULT_THROTTLE_PAUSE : throttlePause;
            requireAtLeastOne(tenantPerMinute, "dokene.admission.send.tenant-per-minute");
            requireAtLeastOne(globalPerMinute, "dokene.admission.send.global-per-minute");
            requireBetween(tenantSharePercent, 1, 100, "dokene.admission.send.tenant-share-percent");
            if (tenantPerDay < tenantPerMinute) {
                throw new IllegalArgumentException(
                        "dokene.admission.send.tenant-per-day must be at least dokene.admission.send.tenant-per-minute");
            }
            if (throttlePause.compareTo(Duration.ofSeconds(1)) < 0
                    || throttlePause.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("dokene.admission.send.throttle-pause must be between 1s and 1h");
            }
        }
    }

    /** Unset or blank binds {@code enabled} to {@code null}, which means off. */
    public record Candidates(Boolean enabled, Integer tenantPerDay) {
        public static final int DEFAULT_TENANT_PER_DAY = 200;

        public Candidates {
            enabled = enabled != null && enabled;
            tenantPerDay = tenantPerDay == null ? DEFAULT_TENANT_PER_DAY : tenantPerDay;
            requireAtLeastOne(tenantPerDay, "dokene.admission.candidates.tenant-per-day");
        }
    }

    private static void requireAtLeastOne(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be 1 or more");
        }
    }

    private static void requireBetween(int value, int low, int high, String name) {
        if (value < low || value > high) {
            throw new IllegalArgumentException(name + " must be between " + low + " and " + high);
        }
    }
}
