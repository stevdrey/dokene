// ABOUTME: Tests the workspace provisioning settings (spec 0021): defaults, startup refusal of an unstated or
// ABOUTME: contradictory policy and of a cap outside its range, the startup line per mode, and the refusal counter.
package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.tenant.application.ProvisioningProperties.Mode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProvisioningConfigurationTest {
    private static final String PREFIX = "dokene.provisioning.";
    private static final String ALLOWLIST_SETTING = "dokene.provisioning.allowed-identities";
    private static final String OPEN_SETTING = "dokene.provisioning.allow-all-identities";
    private static final String FIRST_IDENTITY = "5f0c9a52-6f0e-4c53-9d3e-0b7a4f6f2a11";
    private static final String SECOND_IDENTITY = "c1d2e3f4-0a1b-4c2d-8e3f-4a5b6c7d8e9f";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ProvisioningConfiguration.class);

    /** covers: AC-1, AC-11 */
    @Test
    void bindsTheDocumentedDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            ProvisioningProperties properties = context.getBean(ProvisioningProperties.class);
            assertThat(properties).isEqualTo(new ProvisioningProperties(false, List.of(), false, 3));
            assertThat(properties.mode()).isEqualTo(Mode.DISABLED);
        });
    }

    /** covers: AC-2 */
    @ParameterizedTest
    @ValueSource(strings = {"", ",", " , "})
    void refusesToStartWhenEnabledWithoutAStatedPolicy(String allowlist) {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "allowed-identities=" + allowlist)
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining(ALLOWLIST_SETTING)
                        .hasMessageContaining(OPEN_SETTING));
    }

    /** covers: AC-3 */
    @Test
    void refusesToStartWhenTheOpenSettingAndAnAllowlistAreBothSet() {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "allow-all-identities=true",
                        PREFIX + "allowed-identities=" + FIRST_IDENTITY)
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining(ALLOWLIST_SETTING)
                        .hasMessageContaining(OPEN_SETTING));
    }

    /** covers: AC-1 */
    @Test
    void startsDisabledWithAnyWellFormedAllowlistAndOpenSetting() {
        runner.withPropertyValues(PREFIX + "allow-all-identities=true",
                        PREFIX + "allowed-identities=" + FIRST_IDENTITY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProvisioningProperties.class).mode()).isEqualTo(Mode.DISABLED);
                });
        runner.withPropertyValues(PREFIX + "allow-all-identities=true")
                .run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues(PREFIX + "allowed-identities=" + FIRST_IDENTITY)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** covers: AC-1, AC-4 */
    @ParameterizedTest
    @ValueSource(strings = {"true", "false"})
    void refusesToStartOnAMalformedAllowlistEntryInEveryMode(String enabled) {
        runner.withPropertyValues(PREFIX + "enabled=" + enabled,
                        PREFIX + "allowed-identities=" + FIRST_IDENTITY + ",not-a-uuid")
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining(ALLOWLIST_SETTING)
                        .hasMessageNotContaining("not-a-uuid"));
    }

    /** covers: AC-4. UUID.fromString alone accepts these and binds them as some other identity. */
    @ParameterizedTest
    @ValueSource(strings = {
            "1-2-3-4-5",
            "5f0c9a52-6f0e-4c53-9d3e-0b7a4f6f2a1",
            "5f0c9a526f0e4c539d3e0b7a4f6f2a11",
            "5f0c9a52-6f0e-4c53-9d3e-0b7a4f6f2a11-0",
            "g f0c9a52-6f0e-4c53-9d3e-0b7a4f6f2a11"})
    void refusesToStartOnAnEntryThatIsNotACanonicalUuid(String entry) {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "allowed-identities=" + SECOND_IDENTITY + "," + entry)
                .run(context -> assertThat(context).getFailure().rootCause()
                        .hasMessageContaining(ALLOWLIST_SETTING)
                        .hasMessageNotContaining(entry));
    }

    /** covers: AC-4 */
    @Test
    void trimsEntriesAndDropsBlanksAndDuplicates() {
        runner.withPropertyValues(PREFIX + "enabled=true",
                        PREFIX + "allowed-identities= " + FIRST_IDENTITY + " ,, " + SECOND_IDENTITY + ","
                                + FIRST_IDENTITY.toUpperCase())
                .run(context -> {
                    ProvisioningProperties properties = context.getBean(ProvisioningProperties.class);
                    assertThat(properties.mode()).isEqualTo(Mode.ALLOWLIST);
                    assertThat(properties.allowedIdentities()).containsExactly(FIRST_IDENTITY, SECOND_IDENTITY);
                    assertThat(properties.allowedIdentityIds())
                            .containsExactlyInAnyOrder(UUID.fromString(FIRST_IDENTITY), UUID.fromString(SECOND_IDENTITY));
                });
    }

    /** covers: AC-5 */
    @Test
    void anEmptyAllowlistWithTheOpenSettingIsOpenMode() {
        runner.withPropertyValues(PREFIX + "enabled=true", PREFIX + "allow-all-identities=true",
                        PREFIX + "allowed-identities=,")
                .run(context -> assertThat(context.getBean(ProvisioningProperties.class).mode())
                        .isEqualTo(Mode.OPEN));
    }

    /** covers: AC-11 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10001"})
    void refusesToStartOnACapOutsideItsRangeInEveryMode(String cap) {
        String capSetting = PREFIX + "max-workspaces-per-identity=" + cap;
        runner.withPropertyValues(capSetting).run(context -> assertThat(context).getFailure().rootCause()
                .hasMessageContaining("dokene.provisioning.max-workspaces-per-identity"));
        runner.withPropertyValues(capSetting, PREFIX + "enabled=true", PREFIX + "allow-all-identities=true")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(capSetting, PREFIX + "enabled=true",
                        PREFIX + "allowed-identities=" + FIRST_IDENTITY)
                .run(context -> assertThat(context).hasFailed());
    }

    /** covers: AC-11 */
    @ParameterizedTest
    @ValueSource(ints = {1, 10_000})
    void acceptsTheEdgesOfTheCapRange(int cap) {
        runner.withPropertyValues(PREFIX + "max-workspaces-per-identity=" + cap).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProvisioningProperties.class).maxWorkspacesPerIdentity()).isEqualTo(cap);
        });
    }

    /** covers: AC-15 */
    @Test
    void logsTheDisabledModeAtInfo() {
        assertThat(startupLine()).satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.INFO);
            assertThat(line.getFormattedMessage()).contains("disabled").contains("3");
        });
    }

    /** covers: AC-15 */
    @Test
    void logsTheAllowlistModeAtInfoWithTheNumberOfListedIdentitiesAndNoIdentityId() {
        ILoggingEvent line = startupLine(PREFIX + "enabled=true", PREFIX + "max-workspaces-per-identity=7",
                PREFIX + "allowed-identities=" + FIRST_IDENTITY + "," + SECOND_IDENTITY + "," + FIRST_IDENTITY);

        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getFormattedMessage())
                .contains("allowlist")
                .contains("2 listed identities")
                .contains("7")
                .doesNotContain(FIRST_IDENTITY)
                .doesNotContain(SECOND_IDENTITY);
    }

    /** covers: AC-15 */
    @Test
    void logsTheOpenModeAtWarn() {
        ILoggingEvent line = startupLine(PREFIX + "enabled=true", PREFIX + "allow-all-identities=true",
                PREFIX + "max-workspaces-per-identity=1000");

        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getFormattedMessage()).contains("open").contains("1000");
    }

    /** covers: AC-15 */
    @Test
    void countsRefusalsWithTheReasonAsTheOnlyTag() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new).run(context -> {
            MeterRegistry registry = context.getBean(MeterRegistry.class);
            ProvisioningRefusalCounter counter = context.getBean(ProvisioningRefusalCounter.class);

            counter.refused(ProvisioningRefusalCounter.Reason.POLICY);
            counter.refused(ProvisioningRefusalCounter.Reason.POLICY);

            assertThat(registry.get("dokene.provisioning.refused").tag("reason", "policy").counter().count())
                    .isEqualTo(2);
            assertThat(registry.getMeters()).extracting(Meter::getId)
                    .filteredOn(id -> id.getName().equals("dokene.provisioning.refused"))
                    .singleElement()
                    .satisfies(id -> assertThat(id.getTags()).containsExactly(Tag.of("reason", "policy")));
        });
    }

    /** covers: AC-15 */
    @Test
    void countingWorksWithoutAMeterRegistry() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(ProvisioningRefusalCounter.class).refused(ProvisioningRefusalCounter.Reason.POLICY);
        });
    }

    private ILoggingEvent startupLine(String... settings) {
        try (LogCapture logs = new LogCapture()) {
            runner.withPropertyValues(settings).run(context -> assertThat(context).hasNotFailed());
            List<ILoggingEvent> lines = logs.events().stream()
                    .filter(event -> event.getLoggerName().equals(ProvisioningConfiguration.class.getName()))
                    .toList();
            assertThat(lines).hasSize(1);
            return lines.getFirst();
        }
    }
}
