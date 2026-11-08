package io.github.stevdrey.dokene.ai.provider.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OpenAiConfiguration.class));

    @Test
    void registersBeansWhenProviderIsOpenAiAndKeyConfigured() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key",
                "dokene.ai.openai.model=gpt-6-luna",
                "dokene.ai.openai.max-retries=0"
        ).run(context -> {
            assertThat(context).hasSingleBean(OpenAIClient.class);
            assertThat(context).hasSingleBean(AiProvider.class);
            assertThat(context.getBean(AiProvider.class)).isInstanceOf(OpenAiResponsesApiAdapter.class);
            OpenAiProviderProperties props = context.getBean(OpenAiProviderProperties.class);
            assertThat(props.maxRetries()).isEqualTo(0);
        });
    }

    @Test
    void doesNotRegisterBeansWhenProviderIsFakeOrMissing() {
        runner.withPropertyValues("dokene.ai.provider=fake")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OpenAIClient.class);
                    assertThat(context).doesNotHaveBean(AiProvider.class);
                });

        runner.run(context -> {
            assertThat(context).doesNotHaveBean(OpenAIClient.class);
            assertThat(context).doesNotHaveBean(AiProvider.class);
        });
    }

    @Test
    void failsStartupWhenOpenAiProviderConfiguredWithoutApiKey() {
        runner.withPropertyValues("dokene.ai.provider=openai")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("OpenAI API key must be configured when dokene.ai.provider is set to 'openai'");
                });
    }

    @Test
    void failsStartupWhenMaxRetriesConfiguredGreaterThanZero() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key",
                "dokene.ai.openai.max-retries=2"
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("dokene.ai.openai.max-retries must be 0 to enforce single-invocation timeout determinism; "
                            + "retries are owned by the domain-level decorator configured with dokene.ai.retry.*");
        });
    }

    @Test
    void validatesMaxRetriesInPropertiesConstructor() {
        OpenAiProviderProperties defaultProps = new OpenAiProviderProperties("key", null, null, null, null, null);
        assertThat(defaultProps.maxRetries()).isEqualTo(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new OpenAiProviderProperties("key", null, null, null, 1, null)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dokene.ai.openai.max-retries must be 0");
    }

    @Test
    void defaultsTheOutputTokenCeilingWhenUnset() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key"
        ).run(context -> assertThat(context.getBean(OpenAiProviderProperties.class).maxOutputTokens())
                .isEqualTo(OpenAiProviderProperties.DEFAULT_MAX_OUTPUT_TOKENS)
                .isEqualTo(4096));
    }

    @Test
    void anEmptyOutputTokenCeilingFallsBackToTheDefault() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key",
                "dokene.ai.openai.max-output-tokens="
        ).run(context -> assertThat(context.getBean(OpenAiProviderProperties.class).maxOutputTokens())
                .isEqualTo(OpenAiProviderProperties.DEFAULT_MAX_OUTPUT_TOKENS));
    }

    @Test
    void theShippedApplicationYamlGivesTheDefaultWhetherTheVariableIsUnsetOrEmpty() {
        ApplicationContextRunner shipped = runner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("dokene.ai.provider=openai", "dokene.ai.openai.api-key=test-api-key");

        shipped.run(context -> assertThat(context.getBean(OpenAiProviderProperties.class).maxOutputTokens())
                .isEqualTo(OpenAiProviderProperties.DEFAULT_MAX_OUTPUT_TOKENS));
        // compose.yaml passes the variable as an empty string when it is not set
        shipped.withSystemProperties("DOKENE_AI_OPENAI_MAX_OUTPUT_TOKENS=")
                .run(context -> assertThat(context.getBean(OpenAiProviderProperties.class).maxOutputTokens())
                        .isEqualTo(OpenAiProviderProperties.DEFAULT_MAX_OUTPUT_TOKENS));
        shipped.withSystemProperties("DOKENE_AI_OPENAI_MAX_OUTPUT_TOKENS=2048")
                .run(context -> assertThat(context.getBean(OpenAiProviderProperties.class).maxOutputTokens())
                        .isEqualTo(2048));
    }

    @Test
    void bindsAConfiguredOutputTokenCeiling() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key",
                "dokene.ai.openai.max-output-tokens=2048"
        ).run(context -> assertThat(context.getBean(OpenAiProviderProperties.class).maxOutputTokens())
                .isEqualTo(2048));
    }

    @Test
    void failsStartupWhenTheOutputTokenCeilingIsOutOfRange() {
        for (String value : new String[] {"255", "16385", "0", "-1"}) {
            runner.withPropertyValues(
                    "dokene.ai.provider=openai",
                    "dokene.ai.openai.api-key=test-api-key",
                    "dokene.ai.openai.max-output-tokens=" + value
            ).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .hasRootCauseMessage("dokene.ai.openai.max-output-tokens must be between 256 and 16384");
            });
        }
    }

    @Test
    void acceptsTheOutputTokenCeilingBounds() {
        assertThat(new OpenAiProviderProperties("key", null, null, null, 0, 256).maxOutputTokens()).isEqualTo(256);
        assertThat(new OpenAiProviderProperties("key", null, null, null, 0, 16384).maxOutputTokens()).isEqualTo(16384);
    }

    @Test
    void failsStartupBeforeAnyClientIsBuiltWhenTheBaseUrlIsUnsafe() {
        for (String unsafe : new String[] {"http://api.openai.com/v1", "https://api.openai.com.evil.example/v1",
                "https://api.openai.com@evil.example/v1", "https://api.openai.com/v1?x=1", "http://10.0.0.5/v1",
                "https://ai-gateway.internal.example/v1", "not a url"}) {
            runner.withPropertyValues(
                    "dokene.ai.provider=openai",
                    "dokene.ai.openai.api-key=test-api-key",
                    "dokene.ai.openai.base-url=" + unsafe
            ).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalArgumentException.class)
                        .rootCause().hasMessageStartingWith("dokene.ai.openai.base-url must be https on api.openai.com");
            });
        }
    }

    @Test
    void bindsTheCanonicalEndpointAndExplicitlyTrustedHosts() {
        runner.withPropertyValues(
                "dokene.ai.provider=openai",
                "dokene.ai.openai.api-key=test-api-key",
                "dokene.ai.openai.base-url=https://AI-Gateway.internal.example/v1",
                "dokene.ai.openai.additional-trusted-hosts=ai-gateway.internal.example,backup.internal.example"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            OpenAiProviderProperties props = context.getBean(OpenAiProviderProperties.class);
            assertThat(props.baseUrl()).isEqualTo("https://ai-gateway.internal.example/v1");
            assertThat(props.additionalTrustedHosts())
                    .containsExactly("ai-gateway.internal.example", "backup.internal.example");
        });
    }

    @Test
    void theShippedApplicationYamlDefaultsToTheOpenAiEndpointWithNoTrustedHosts() {
        ApplicationContextRunner shipped = runner
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("dokene.ai.provider=openai", "dokene.ai.openai.api-key=test-api-key");

        shipped.run(context -> {
            OpenAiProviderProperties props = context.getBean(OpenAiProviderProperties.class);
            assertThat(props.baseUrl()).isEqualTo("https://api.openai.com/v1");
            assertThat(props.additionalTrustedHosts()).isEmpty();
        });
        // compose.yaml passes unset variables as empty strings
        shipped.withSystemProperties("DOKENE_AI_OPENAI_BASE_URL=", "DOKENE_AI_OPENAI_ADDITIONAL_TRUSTED_HOSTS=")
                .run(context -> {
                    OpenAiProviderProperties props = context.getBean(OpenAiProviderProperties.class);
                    assertThat(props.baseUrl()).isEqualTo("https://api.openai.com/v1");
                    assertThat(props.additionalTrustedHosts()).isEmpty();
                });
        // the local fault-injection stub of the verification suites keeps working
        shipped.withSystemProperties("DOKENE_AI_OPENAI_BASE_URL=http://127.0.0.1:18080/v1")
                .run(context -> assertThat(context.getBean(OpenAiProviderProperties.class).baseUrl())
                        .isEqualTo("http://127.0.0.1:18080/v1"));
        shipped.withSystemProperties("DOKENE_AI_OPENAI_BASE_URL=https://ai-gateway.internal.example/v1",
                        "DOKENE_AI_OPENAI_ADDITIONAL_TRUSTED_HOSTS=ai-gateway.internal.example")
                .run(context -> assertThat(context.getBean(OpenAiProviderProperties.class).baseUrl())
                        .isEqualTo("https://ai-gateway.internal.example/v1"));
        shipped.withSystemProperties("DOKENE_AI_OPENAI_BASE_URL=http://evil.example/v1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theConfiguredClientNeverFollowsARedirectToAnotherHost() throws IOException {
        AtomicInteger redirectorHits = new AtomicInteger();
        AtomicInteger targetHits = new AtomicInteger();
        HttpServer target = loopbackServer(exchange -> {
            targetHits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        String location = "http://localhost:" + target.getAddress().getPort() + "/v1/models";
        HttpServer redirector = loopbackServer(exchange -> {
            redirectorHits.incrementAndGet();
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        String baseUrl = "http://127.0.0.1:" + redirector.getAddress().getPort() + "/v1";
        try {
            runner.withPropertyValues(
                    "dokene.ai.provider=openai",
                    "dokene.ai.openai.api-key=test-api-key",
                    "dokene.ai.openai.base-url=" + baseUrl
            ).run(context -> {
                OpenAIClient client = context.getBean(OpenAIClient.class);
                assertThatThrownBy(() -> client.models().list()).isInstanceOf(RuntimeException.class);
                assertThat(redirectorHits).hasValue(1);
                assertThat(targetHits).hasValue(0);
            });

            // Control: the SDK default follows the redirect, so the assertion above is meaningful.
            OpenAIClient following = OpenAIOkHttpClient.builder().apiKey("test-api-key").baseUrl(baseUrl)
                    .maxRetries(0).build();
            try {
                following.models().list();
            } catch (RuntimeException expected) {
                // the empty target response is not a valid page; only the hit matters here
            } finally {
                following.close();
            }
            assertThat(targetHits).hasValue(1);
        } finally {
            redirector.stop(0);
            target.stop(0);
        }
    }

    private static HttpServer loopbackServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }
}
