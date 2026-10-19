// ABOUTME: Boots a real SpringApplication with a gap in the secret entry indices and proves the failure report
// ABOUTME: names properties only. ApplicationContextRunner skips failure analyzers, so this test cannot use it.
package io.github.stevdrey.dokene.integration.secrets;

import static io.github.stevdrey.dokene.integration.testsupport.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** Boot resets Logback during startup, so the console (what an operator and a log shipper see) is captured instead. */
@ExtendWith(OutputCaptureExtension.class)
class SecretBindFailureAnalyzerTest {
    private static final String ENTRIES = "dokene.integration.secrets.local.entries";

    @Test
    void aGapInTheEntryIndicesIsReportedByNameOnlyWhenTheSourceIsLocal(CapturedOutput output) {
        assertGapReportedWithoutValues("local", output);
    }

    /** Binding happens before the bean method, so the leak path exists even with the resolver disabled. */
    @Test
    void aGapInTheEntryIndicesIsReportedByNameOnlyWhenTheSourceIsBlank(CapturedOutput output) {
        assertGapReportedWithoutValues("", output);
    }

    /**
     * Boot's reporter logs the analysis cause with its stack trace at debug level, so the cause handed to the
     * analysis must not be the raw bind exception, which keeps the unbound values.
     */
    @Test
    void aGapInTheEntryIndicesIsReportedByNameOnlyWithDiagnosticsAtDebugLevel(CapturedOutput output) {
        assertGapReportedWithoutValues("local", output,
                Map.of("logging.level.org.springframework.boot.diagnostics", "DEBUG"));

        assertThat(output.getAll())
                .contains("Application failed to start due to an exception")
                .contains(SecretBindFailureAnalyzer.SecretBindFailure.class.getName())
                .doesNotContain("BindException: Failed to bind properties");
    }

    private static void assertGapReportedWithoutValues(String source, CapturedOutput output) {
        assertGapReportedWithoutValues(source, output, Map.of());
    }

    private static void assertGapReportedWithoutValues(String source, CapturedOutput output,
            Map<String, Object> extraProperties) {
        String tenantId = UUID.randomUUID().toString();
        String ref = SecretCanaries.ref();
        String value = SecretCanaries.value();
        String strandedRef = SecretCanaries.ref();
        String strandedValue = SecretCanaries.value();
        List<String> canaries = List.of(ref, value, strandedRef, strandedValue, tenantId);
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("spring.main.banner-mode", "off"),
                Map.entry("dokene.integration.secrets.source", source),
                Map.entry(ENTRIES + "[0].scope", "tenant"),
                Map.entry(ENTRIES + "[0].tenant-id", tenantId),
                Map.entry(ENTRIES + "[0].ref", ref),
                Map.entry(ENTRIES + "[0].purpose", "WHATSAPP_ACCESS_TOKEN"),
                Map.entry(ENTRIES + "[0].value", value),
                Map.entry(ENTRIES + "[2].scope", "deployment"),
                Map.entry(ENTRIES + "[2].ref", strandedRef),
                Map.entry(ENTRIES + "[2].purpose", "APP_SECRET"),
                Map.entry(ENTRIES + "[2].value", strandedValue));
        Map<String, Object> allProperties = new HashMap<>(properties);
        allProperties.putAll(extraProperties);

        Throwable failure = catchThrowable(() -> new SpringApplicationBuilder(IntegrationSecretsConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(allProperties)
                .run());

        assertThat(failure).isNotNull();
        String console = output.getAll();
        assertThat(console)
                .contains("APPLICATION FAILED TO START")
                .contains("Secret entries were left unbound")
                .contains(ENTRIES + "[2].ref")
                .contains("contiguous from 0");
        assertThat(containsAny(console, canaries)).isFalse();
        assertThat(containsAny(causeChainText(failure), canaries)).isFalse();
    }

    private static String causeChainText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null && current.getCause() != current; current = current.getCause()) {
            text.append(current.getClass().getName()).append(": ").append(current.getMessage()).append('\n');
        }
        return text.toString();
    }
}
