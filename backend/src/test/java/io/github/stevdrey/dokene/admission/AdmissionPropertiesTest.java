// ABOUTME: Binds dokene.admission the way Spring Boot does (ADR 0040, spec 0024 AC-15): the documented defaults, every
// ABOUTME: setting, and the refusal of a value outside its bounds, which is what stops startup.
package io.github.stevdrey.dokene.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AdmissionPropertiesTest {
    private static final String PREFIX = "dokene.admission";

    @Test
    void nothingSetGivesTheDocumentedDefaults() {
        AdmissionProperties properties = new AdmissionProperties(null, null, null, null);

        assertThat(properties.send()).isEqualTo(
                new AdmissionProperties.Send(30, 500, 120, 25, Duration.ofSeconds(60)));
        assertThat(properties.candidates()).isEqualTo(new AdmissionProperties.Candidates(false, 200));
        assertThat(properties.lockTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.pruneBatch()).isEqualTo(500);
    }

    @Test
    void everySettingBinds() {
        AdmissionProperties properties = bind(Map.of(PREFIX + ".send.tenant-per-minute", "5",
                PREFIX + ".send.tenant-per-day", "50", PREFIX + ".send.global-per-minute", "40",
                PREFIX + ".send.tenant-share-percent", "10", PREFIX + ".send.throttle-pause", "2m",
                PREFIX + ".candidates.enabled", "true", PREFIX + ".candidates.tenant-per-day", "7",
                PREFIX + ".lock-timeout", "250ms", PREFIX + ".prune-batch", "20"));

        assertThat(properties.send()).isEqualTo(
                new AdmissionProperties.Send(5, 50, 40, 10, Duration.ofMinutes(2)));
        assertThat(properties.candidates()).isEqualTo(new AdmissionProperties.Candidates(true, 7));
        assertThat(properties.lockTimeout()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.pruneBatch()).isEqualTo(20);
    }

    @Test
    void candidateAdmissionIsOffWhenTheSwitchIsUnsetOrBlank() {
        assertThat(bind(Map.of(PREFIX + ".prune-batch", "20")).candidates().enabled()).isFalse();
        assertThat(bind(Map.of(PREFIX + ".candidates.enabled", "", PREFIX + ".prune-batch", "20")).candidates().enabled()).isFalse();
        assertThat(bind(Map.of(PREFIX + ".candidates.enabled", "false")).candidates().enabled()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"send.tenant-per-minute,0", "send.tenant-per-day,29", "send.global-per-minute,0",
            "send.tenant-share-percent,0", "send.tenant-share-percent,101", "send.throttle-pause,500ms",
            "send.throttle-pause,2h", "candidates.tenant-per-day,0", "candidates.enabled,maybe",
            "lock-timeout,50ms", "lock-timeout,6s", "prune-batch,0", "prune-batch,5001"})
    void aValueOutsideItsBoundsFailsTheBinding(String setting, String value) {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + "." + setting, value))).isInstanceOf(BindException.class);
    }

    @Test
    void aDayLimitBelowTheMinuteLimitFailsTheBinding() {
        assertThatThrownBy(() -> bind(Map.of(PREFIX + ".send.tenant-per-minute", "10",
                PREFIX + ".send.tenant-per-day", "9"))).isInstanceOf(BindException.class);
        assertThat(bind(Map.of(PREFIX + ".send.tenant-per-minute", "10", PREFIX + ".send.tenant-per-day", "10"))
                .send().tenantPerDay()).isEqualTo(10);
    }

    private static AdmissionProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(PREFIX, AdmissionProperties.class).get();
    }
}
