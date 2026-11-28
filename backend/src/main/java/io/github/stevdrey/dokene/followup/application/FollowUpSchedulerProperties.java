// ABOUTME: Settings of the recurring follow-up scheduler (issue 463, ADR 0040): off by default, separate kill switches for
// ABOUTME: generation and processing, bounded tenants and work per tick, a tick budget and a graceful shutdown wait.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpWorkerId;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Read once at startup; changing a switch needs a restart, and no API, tenant setting or header can change one.
 *
 * @param enabled the scheduler's master switch; {@code false} (the default) runs nothing at all
 * @param generationEnabled whether ticks scan for due candidates (ADR 0036); only effective while {@code enabled}
 * @param processingEnabled whether ticks enqueue and process work (ADR 0037, ADR 0039); only effective while
 *        {@code enabled}
 * @param interval the delay between the end of one tick and the start of the next, on the scheduler's own thread
 * @param tenantsPerTick the most tenants one tick visits; the rotation resumes after the last one on the next tick
 * @param workBatchSize the most work items one tenant's processing may claim per tick; at most
 *        {@code dokene.follow-up.work.batch-size}
 * @param maxTickDuration no new tenant starts once a tick has run this long; the tenant in progress finishes
 * @param shutdownTimeout how long shutdown waits for a running tick to finish its current tenant
 * @param workerId this instance's label on its claims (ADR 0037); a random {@code dokene-…} label when not set
 */
@ConfigurationProperties(prefix = "dokene.follow-up.scheduler")
public record FollowUpSchedulerProperties(Boolean enabled, Boolean generationEnabled, Boolean processingEnabled,
        Duration interval, Integer tenantsPerTick, Integer workBatchSize, Duration maxTickDuration,
        Duration shutdownTimeout, String workerId) {
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(1);
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(10);
    public static final Duration MAX_INTERVAL = Duration.ofHours(1);
    public static final int DEFAULT_TENANTS_PER_TICK = 50;
    public static final int MAX_TENANTS_PER_TICK = 500;
    public static final int DEFAULT_WORK_BATCH_SIZE = 10;
    public static final Duration DEFAULT_MAX_TICK_DURATION = Duration.ofSeconds(30);
    public static final Duration MIN_MAX_TICK_DURATION = Duration.ofSeconds(1);
    public static final Duration MAX_MAX_TICK_DURATION = Duration.ofMinutes(30);
    public static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration MAX_SHUTDOWN_TIMEOUT = Duration.ofMinutes(5);

    public FollowUpSchedulerProperties {
        enabled = enabled != null && enabled;
        generationEnabled = generationEnabled == null || generationEnabled;
        processingEnabled = processingEnabled == null || processingEnabled;
        interval = interval == null ? DEFAULT_INTERVAL : interval;
        tenantsPerTick = tenantsPerTick == null ? DEFAULT_TENANTS_PER_TICK : tenantsPerTick;
        workBatchSize = workBatchSize == null ? DEFAULT_WORK_BATCH_SIZE : workBatchSize;
        maxTickDuration = maxTickDuration == null ? DEFAULT_MAX_TICK_DURATION : maxTickDuration;
        shutdownTimeout = shutdownTimeout == null ? DEFAULT_SHUTDOWN_TIMEOUT : shutdownTimeout;
        workerId = workerId == null || workerId.isBlank() ? randomWorkerId() : workerId.strip();
        if (interval.compareTo(MIN_INTERVAL) < 0 || interval.compareTo(MAX_INTERVAL) > 0) {
            throw new IllegalArgumentException("dokene.follow-up.scheduler.interval must be between 10 seconds and 1 hour");
        }
        if (tenantsPerTick < 1 || tenantsPerTick > MAX_TENANTS_PER_TICK) {
            throw new IllegalArgumentException(
                    "dokene.follow-up.scheduler.tenants-per-tick must be between 1 and " + MAX_TENANTS_PER_TICK);
        }
        if (workBatchSize < 1 || workBatchSize > FollowUpWorkProperties.MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("dokene.follow-up.scheduler.work-batch-size must be between 1 and "
                    + FollowUpWorkProperties.MAX_BATCH_SIZE);
        }
        if (maxTickDuration.compareTo(MIN_MAX_TICK_DURATION) < 0
                || maxTickDuration.compareTo(MAX_MAX_TICK_DURATION) > 0) {
            throw new IllegalArgumentException(
                    "dokene.follow-up.scheduler.max-tick-duration must be between 1 second and 30 minutes");
        }
        if (shutdownTimeout.isNegative() || shutdownTimeout.compareTo(MAX_SHUTDOWN_TIMEOUT) > 0) {
            throw new IllegalArgumentException(
                    "dokene.follow-up.scheduler.shutdown-timeout must be between 0 and 5 minutes");
        }
        // Validates the label format (ADR 0037) at startup rather than on the first claim.
        new FollowUpWorkerId(workerId);
    }

    /** Whether ticks scan for candidates: both the master switch and the generation switch are on. */
    public boolean generationActive() {
        return enabled && generationEnabled;
    }

    /** Whether ticks enqueue and process work: both the master switch and the processing switch are on. */
    public boolean processingActive() {
        return enabled && processingEnabled;
    }

    public FollowUpWorkerId worker() {
        return new FollowUpWorkerId(workerId);
    }

    private static String randomWorkerId() {
        byte[] suffix = new byte[4];
        ThreadLocalRandom.current().nextBytes(suffix);
        return "dokene-" + HexFormat.of().formatHex(suffix);
    }
}
