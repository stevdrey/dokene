// ABOUTME: Context runner proof of spec 0007 AC-3, AC-6, AC-8 and AC-10 on the real local secret source.
// ABOUTME: Opens LogCapture before run so startup WARN lines are seen, and proves no canary reaches any log or cause.
package io.github.stevdrey.dokene.integration.whatsapp.webhook.verify;

import static io.github.stevdrey.dokene.integration.testsupport.SecretCanaries.containsAny;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import ch.qos.logback.classic.spi.ILoggingEvent;
import io.github.stevdrey.dokene.integration.secrets.IntegrationSecretsConfiguration;
import io.github.stevdrey.dokene.integration.testsupport.LogCapture;
import io.github.stevdrey.dokene.integration.testsupport.SecretCanaries;
import io.github.stevdrey.dokene.integration.testsupport.WebhookSigning;
import io.github.stevdrey.dokene.integration.whatsapp.webhook.verify.WebhookVerificationException.Reason;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class WhatsAppWebhookConfigurationTest {
    private static final String ENTRIES = "dokene.integration.secrets.local.entries";
    private static final String APP_SECRET_REF = "dokene.integration.whatsapp.app-secret-ref";
    private static final String VERIFY_TOKEN_REF = "dokene.integration.whatsapp.verify-token-ref";
    private static final String MAX_BODY_BYTES = "dokene.integration.whatsapp.webhook.max-body-bytes";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IntegrationSecretsConfiguration.class, WhatsAppWebhookConfiguration.class);

    private final String appSecret = SecretCanaries.value();
    private final String appSecretRef = SecretCanaries.ref();
    private final String verifyToken = SecretCanaries.value();
    private final String verifyTokenRef = SecretCanaries.ref();
    private final String bodyCanary = "body-" + SecretCanaries.value();
    private final String challenge = "challenge-" + SecretCanaries.value();
    private final byte[] body = WebhookSigning.body(bodyCanary);
    private final String signature = WebhookSigning.sign(body, appSecret.toCharArray());
    private final List<String> canaries = List.of(appSecret, verifyToken, bodyCanary, challenge, signature);

    private LogCapture logs;

    @BeforeEach
    void setUp() {
        logs = new LogCapture();
    }

    @AfterEach
    void tearDown() {
        boolean leaked = containsAny(logs.text(), canaries);
        logs.close();
        assertThat(leaked).isFalse();
    }

    @Test
    void aConfiguredStackVerifiesASignedBodyAndEchoesTheChallenge() {
        runner.withPropertyValues(configured()).run(context -> {
            assertThat(context).hasSingleBean(WebhookSignatureVerifier.class)
                    .hasSingleBean(WebhookChallengeVerifier.class)
                    .hasSingleBean(BoundedWebhookBodyReader.class)
                    .hasSingleBean(WebhookTelemetry.class);
            assertThat(context.getBean(WebhookTelemetry.class)).isInstanceOf(NoOpWebhookTelemetry.class);

            VerifiedWebhookBody verified = context.getBean(WebhookSignatureVerifier.class)
                    .verify(body, List.of(signature));
            assertThat(verified.copy()).isEqualTo(body);
            assertThat(context.getBean(WebhookChallengeVerifier.class).verify("subscribe", verifyToken, challenge))
                    .isEqualTo(challenge);
            assertThat(context.getBean(WhatsAppWebhookProperties.class).toString())
                    .isEqualTo("WhatsAppWebhookProperties[REDACTED]");
        });
        assertThat(refusingWarnings()).isEmpty();
    }

    @Test
    void aMeterRegistryBeanSelectsTheMicrometerTelemetry() {
        runner.withUserConfiguration(RegistryConfiguration.class).withPropertyValues(configured()).run(context -> {
            assertThat(context.getBean(WebhookTelemetry.class)).isInstanceOf(MicrometerWebhookTelemetry.class);

            WebhookVerificationException refusal = catchThrowableOfType(WebhookVerificationException.class,
                    () -> context.getBean(WebhookSignatureVerifier.class).verify(body, List.of()));

            assertThat(refusal.reason()).isEqualTo(Reason.MISSING_SIGNATURE);
            assertThat(context.getBean(MeterRegistry.class).get("dokene.webhook.rejected")
                    .tag("reason", "MISSING_SIGNATURE").counter().count()).isEqualTo(1.0);
        });
    }

    @Test
    void unsetReferencesPutBothPathsInTheRefusingStateAndWarnOnceEachAtStartup() {
        runner.withPropertyValues(local(deploymentEntry(0, appSecretRef, "APP_SECRET", appSecret),
                deploymentEntry(1, verifyTokenRef, "WEBHOOK_VERIFY_TOKEN", verifyToken))).run(context -> {
            assertThat(reasonOf(() -> context.getBean(WebhookSignatureVerifier.class).verify(body, List.of(signature))))
                    .isEqualTo(Reason.SECRET_UNAVAILABLE);
            assertThat(reasonOf(() -> context.getBean(WebhookChallengeVerifier.class)
                    .verify("subscribe", verifyToken, challenge))).isEqualTo(Reason.SECRET_UNAVAILABLE);
        });
        assertThat(refusingWarnings()).containsExactlyInAnyOrder(
                "Webhook verification refusing: " + APP_SECRET_REF + " is unset",
                "Webhook verification refusing: " + VERIFY_TOKEN_REF + " is unset");
    }

    @Test
    void aBlankReferenceCountsAsUnset() {
        runner.withPropertyValues(plus(local(), APP_SECRET_REF + "=", VERIFY_TOKEN_REF + "=  ")).run(context ->
                assertThat(context).hasNotFailed());
        assertThat(refusingWarnings()).hasSize(2);
    }

    @Test
    void thePathsAreIndependent() {
        runner.withPropertyValues(plus(local(deploymentEntry(0, verifyTokenRef, "WEBHOOK_VERIFY_TOKEN", verifyToken)),
                VERIFY_TOKEN_REF + "=" + verifyTokenRef)).run(context -> {
            assertThat(context.getBean(WebhookChallengeVerifier.class).verify("subscribe", verifyToken, challenge))
                    .isEqualTo(challenge);
            assertThat(reasonOf(() -> context.getBean(WebhookSignatureVerifier.class).verify(body, List.of(signature))))
                    .isEqualTo(Reason.SECRET_UNAVAILABLE);
        });
        assertThat(refusingWarnings()).containsExactly("Webhook verification refusing: " + APP_SECRET_REF + " is unset");
    }

    @Test
    void aMalformedReferenceFailsStartupNamingThePropertyAndNeverTheValue() {
        String pastedSecret = "pasted secret " + SecretCanaries.value();
        for (String property : List.of(APP_SECRET_REF, VERIFY_TOKEN_REF)) {
            runner.withPropertyValues(plus(local(), property + "=" + pastedSecret)).run(context -> {
                assertThat(context).hasFailed();
                List<String> chain = causeChain(context.getStartupFailure());
                assertThat(chain).anyMatch(message -> message.contains(property + " is not a valid secret reference"));
                assertThat(containsAny(String.join("\n", chain), List.of(pastedSecret))).isFalse();
            });
        }
        // The runner trims "key=value" pairs, so the padded value goes in through a property source untouched.
        runner.withPropertyValues(local()).withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("padded", Map.of(APP_SECRET_REF, " " + appSecretRef))))
                .run(context -> assertThat(context).hasFailed());
    }

    /** covers: AC-6, a whitespace padded reference is not trimmed; startup fails naming the property, never the value. */
    @Test
    void aWhitespacePaddedReferenceFailsStartupNamingTheProperty() {
        String ref = SecretCanaries.ref();
        for (String property : List.of(APP_SECRET_REF, VERIFY_TOKEN_REF)) {
            for (String padded : List.of(" " + ref, ref + " ", ref + "\n", "\t" + ref)) {
                runner.withPropertyValues(local()).withInitializer(context -> context.getEnvironment()
                        .getPropertySources().addFirst(new MapPropertySource("padded", Map.of(property, padded))))
                        .run(context -> {
                            assertThat(context).hasFailed();
                            List<String> chain = causeChain(context.getStartupFailure());
                            assertThat(chain).as(property + " <- " + padded.strip())
                                    .anyMatch(message -> message.contains(property + " is not a valid secret reference"));
                            assertThat(containsAny(String.join("\n", chain), List.of(padded))).isFalse();
                        });
            }
        }
    }

    @Test
    void theBodyBoundIsValidatedAtStartupAndDefaultsToFourMebibytes() {
        for (String outside : List.of("0", "16777217", "-1")) {
            runner.withPropertyValues(plus(local(), MAX_BODY_BYTES + "=" + outside)).run(context -> {
                assertThat(context).hasFailed();
                assertThat(causeChain(context.getStartupFailure())).anyMatch(message ->
                        message.contains(MAX_BODY_BYTES + " must be between 1 and 16777216"));
            });
        }
        runner.withPropertyValues(plus(local(), MAX_BODY_BYTES + "=1")).run(context -> {
            BoundedWebhookBodyReader reader = context.getBean(BoundedWebhookBodyReader.class);
            assertThat(reader.read(new ByteArrayInputStream(new byte[1]), 1)).hasSize(1);
            assertThat(reasonOf(() -> reader.read(new ByteArrayInputStream(new byte[2]), -1)))
                    .isEqualTo(Reason.BODY_TOO_LARGE);
        });
        runner.withPropertyValues(local()).run(context -> {
            BoundedWebhookBodyReader reader = context.getBean(BoundedWebhookBodyReader.class);
            assertThat(reader.read(new ByteArrayInputStream(new byte[4_194_304]), -1)).hasSize(4_194_304);
            assertThat(reasonOf(() -> reader.read(new ByteArrayInputStream(new byte[4_194_305]), -1)))
                    .isEqualTo(Reason.BODY_TOO_LARGE);
        });
    }

    private String[] configured() {
        List<String> properties = new ArrayList<>(List.of(local(
                deploymentEntry(0, appSecretRef, "APP_SECRET", appSecret),
                deploymentEntry(1, verifyTokenRef, "WEBHOOK_VERIFY_TOKEN", verifyToken))));
        properties.add(APP_SECRET_REF + "=" + appSecretRef);
        properties.add(VERIFY_TOKEN_REF + "=" + verifyTokenRef);
        return properties.toArray(String[]::new);
    }

    private List<String> refusingWarnings() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : logs.warningsFrom(WhatsAppWebhookConfiguration.class)) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    private static List<String> causeChain(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(cause.getClass().getName() + ": " + cause.getMessage());
        }
        return messages;
    }

    private static Reason reasonOf(Runnable call) {
        return catchThrowableOfType(WebhookVerificationException.class, call::run).reason();
    }

    private static String[] local(String[]... entries) {
        List<String> properties = new ArrayList<>();
        properties.add("dokene.integration.secrets.source=local");
        for (String[] entry : entries) {
            properties.addAll(List.of(entry));
        }
        return properties.toArray(String[]::new);
    }

    private static String[] plus(String[] base, String... extra) {
        List<String> properties = new ArrayList<>(List.of(base));
        properties.addAll(List.of(extra));
        return properties.toArray(String[]::new);
    }

    private static String[] deploymentEntry(int index, String ref, String purpose, String value) {
        String prefix = ENTRIES + "[" + index + "].";
        return new String[] {prefix + "scope=deployment", prefix + "ref=" + ref, prefix + "purpose=" + purpose,
                prefix + "value=" + value};
    }

    @Configuration(proxyBeanMethods = false)
    static class RegistryConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
