// ABOUTME: Binds dokene.messaging.provider-health (ADR 0038) with its defaults and validates it at startup through
// ABOUTME: ProviderHealthSettings, so a value outside its bounds or an unknown mode stops the application.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dokene.messaging.provider-health")
public record ProviderHealthProperties(Mode mode, Integer outageThreshold, Duration outageWindow,
        Integer recoverySuccesses, Duration staleAfter, Duration coolDownInitial, Duration coolDownMax,
        Duration probeLease) {
    public static final int DEFAULT_OUTAGE_THRESHOLD = 3;
    public static final Duration DEFAULT_OUTAGE_WINDOW = Duration.ofMinutes(2);
    public static final int DEFAULT_RECOVERY_SUCCESSES = 2;
    public static final Duration DEFAULT_STALE_AFTER = Duration.ofMinutes(15);
    public static final Duration DEFAULT_COOL_DOWN_INITIAL = Duration.ofSeconds(30);
    public static final Duration DEFAULT_COOL_DOWN_MAX = Duration.ofMinutes(5);
    public static final Duration DEFAULT_PROBE_LEASE = Duration.ofSeconds(10);

    public ProviderHealthProperties {
        mode = mode == null ? Mode.ENFORCE : mode;
        outageThreshold = outageThreshold == null ? DEFAULT_OUTAGE_THRESHOLD : outageThreshold;
        outageWindow = outageWindow == null ? DEFAULT_OUTAGE_WINDOW : outageWindow;
        recoverySuccesses = recoverySuccesses == null ? DEFAULT_RECOVERY_SUCCESSES : recoverySuccesses;
        staleAfter = staleAfter == null ? DEFAULT_STALE_AFTER : staleAfter;
        coolDownInitial = coolDownInitial == null ? DEFAULT_COOL_DOWN_INITIAL : coolDownInitial;
        coolDownMax = coolDownMax == null ? DEFAULT_COOL_DOWN_MAX : coolDownMax;
        probeLease = probeLease == null ? DEFAULT_PROBE_LEASE : probeLease;
        // Built once here only to validate: a bad value must fail the binding, not the first send.
        new ProviderHealthSettings(mode, outageThreshold, outageWindow, recoverySuccesses, staleAfter,
                coolDownInitial, coolDownMax, probeLease);
    }

    public ProviderHealthSettings settings() {
        return new ProviderHealthSettings(mode, outageThreshold, outageWindow, recoverySuccesses, staleAfter,
                coolDownInitial, coolDownMax, probeLease);
    }
}
