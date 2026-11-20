// ABOUTME: Starts the OAuth client wiring with Boot's own auto-configuration and checks what spec 0019 refuses at
// ABOUTME: startup (AC-3, AC-4, AC-5, AC-7, issue 318) and that the callback is derived from the origin alone.
package io.github.stevdrey.dokene.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

@ExtendWith(OutputCaptureExtension.class)
class RedirectOriginStartupTest {

    private static final String ORIGIN = "http://localhost:8080";
    private static final String REDIRECT = "spring.security.oauth2.client.registration.dokene.redirect-uri";
    private static final String REDIRECT_ENV = "SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_DOKENE_REDIRECT_URI";

    private static final String REPLAY = "dokene.security.oidc.back-channel-logout-uri";

    /** No replay URI: the runner does not read application.yml, the one place its default is written. */
    private final WebApplicationContextRunner withoutReplayUri = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withUserConfiguration(ServerPropertiesConfiguration.class, RedirectOriginConfiguration.class)
            .withPropertyValues("server.forward-headers-strategy=none");

    private final WebApplicationContextRunner withoutRegistration = withoutReplayUri.withPropertyValues(
            REPLAY + "=http://localhost:8080/logout/connect/back-channel/{registrationId}");

    /**
     * The provider is found through its issuer, as on the shipped stack. Only then does Boot give an unset redirect
     * URI a default for the post processor to replace; with hand written endpoints Boot itself refuses it.
     */
    private final WebApplicationContextRunner withRegistration =
            withoutRegistration.withPropertyValues(stubClientRegistration());

    /** covers: AC-4. */
    @Test
    void aClientRegistrationWithoutAnOriginStopsStartup() {
        withRegistration.run(context -> assertStartupRefused(context, "DOKENE_PUBLIC_ORIGIN", "must be set"));
        withRegistration.withPropertyValues("dokene.security.public-origin=")
                .run(context -> assertStartupRefused(context, "DOKENE_PUBLIC_ORIGIN", "must be set"));
    }

    /** covers: AC-4. */
    @Test
    void aMalformedOriginStopsStartupNamingTheRule() {
        withRegistration.withPropertyValues("dokene.security.public-origin=http://dokene.example")
                .run(context -> assertStartupRefused(context, "DOKENE_PUBLIC_ORIGIN", "plain http"));
    }

    /** covers: AC-4, AC-9. A malformed origin stops startup with no registration too: its scheme decides HSTS. */
    @Test
    void aMalformedOriginStopsStartupWithoutAClientRegistration() {
        withoutRegistration.withPropertyValues("dokene.security.public-origin=https://dokene.example/")
                .run(context -> assertStartupRefused(context, "DOKENE_PUBLIC_ORIGIN", "path, query or fragment"));
    }

    /**
     * covers: AC-4, AC-9. Pins the signal the checks rest on: Boot binds {@link OAuth2ClientProperties} only when a
     * client registration is configured. That is Boot internal, so an upgrade that changes it must fail here.
     */
    @Test
    void theClientPropertiesBeanExistsExactlyWhenARegistrationIsConfigured() {
        withoutRegistration.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OAuth2ClientProperties.class);
        });
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OAuth2ClientProperties.class);
        });
    }

    /**
     * covers: AC-4, AC-5, AC-9. Only Boot's repository bean backs off for a hand written repository; the condition
     * that binds {@link OAuth2ClientProperties} reads the registration settings alone. So the settings still
     * require the origin and run the registration checks, and an upgrade that changes this must fail here.
     */
    @Test
    void aHandWrittenRepositoryDoesNotHideTheConfiguredRegistration() {
        WebApplicationContextRunner handWritten =
                withRegistration.withUserConfiguration(DokeneOnlyRepositoryConfiguration.class);

        handWritten.run(context -> assertStartupRefused(context, "DOKENE_PUBLIC_ORIGIN", "must be set"));
        handWritten.withPropertyValues("dokene.security.public-origin=" + ORIGIN, "server.servlet.context-path=/dokene")
                .run(context -> assertStartupRefused(context, "SERVER_SERVLET_CONTEXT_PATH"));
        handWritten.withPropertyValues("dokene.security.public-origin=" + ORIGIN).run(context -> {
            assertThat(dokeneRegistration(context).getRedirectUri())
                    .isEqualTo(ORIGIN + "/login/oauth2/code/{registrationId}");
            assertThat(context).hasSingleBean(OAuth2ClientProperties.class);
        });
    }

    /** covers: AC-5. With no registration settings a hand written repository is refused for what is true of it. */
    @Test
    void aHandWrittenRepositoryWithoutRegistrationSettingsStopsStartup() {
        withoutRegistration.withUserConfiguration(DokeneOnlyRepositoryConfiguration.class)
                .withPropertyValues("dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertStartupRefused(context, "no OAuth client registration is configured"));
    }

    /**
     * covers: AC-7, AC-9. Under lazy initialization nothing asks for the check beans until the first request builds
     * the filter chains, so they must refuse startup on their own.
     */
    @Test
    void aRefusedSettingStopsStartupUnderLazyInitialization() {
        WebApplicationContextRunner lazy = withoutRegistration.withInitializer(
                context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));

        lazy.run(context -> assertThat(context).hasNotFailed());
        lazy.withPropertyValues("dokene.security.public-origin=http://dokene.example")
                .run(context -> assertStartupRefused(context, "DOKENE_PUBLIC_ORIGIN", "plain http"));
        lazy.withPropertyValues("server.forward-headers-strategy=native")
                .run(context -> assertStartupRefused(context, "SERVER_FORWARD_HEADERS_STRATEGY"));
        lazy.withPropertyValues(stubClientRegistration())
                .run(context -> assertStartupRefused(context, "DOKENE_PUBLIC_ORIGIN", "must be set"));
    }

    /** covers: AC-4, AC-9. User info may hold a password, so no message in the chain may repeat the value. */
    @Test
    void aRefusedOriginWithUserInfoAppearsNowhereInTheExceptionChain() {
        withRegistration.withPropertyValues("dokene.security.public-origin=https://user:secret@dokene.example")
                .run(context -> {
                    assertStartupRefused(context, "DOKENE_PUBLIC_ORIGIN", "user info");
                    for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                        assertThat(String.valueOf(cause.getMessage())).doesNotContain("user:", "secret");
                        assertThat(cause.toString()).doesNotContain("user:", "secret");
                    }
                });
    }

    /** covers: AC-4, AC-7. Without a registration there is nothing to send back, so no origin is required. */
    @Test
    void noOriginIsRequiredWithoutAClientRegistration() {
        withoutRegistration.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PublicOriginSettings.class).hstsEnabled()).isFalse();
        });
    }

    /** covers: AC-1, AC-5. */
    @Test
    void anUnsetRedirectUriIsDerivedFromTheOrigin() {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertThat(dokeneRegistration(context).getRedirectUri())
                        .isEqualTo(ORIGIN + "/login/oauth2/code/{registrationId}"));
    }

    /** covers: AC-5. The two values that already say what the origin says are accepted. */
    @ParameterizedTest
    @ValueSource(strings = {ORIGIN + "/login/oauth2/code/{registrationId}", ORIGIN + "/login/oauth2/code/dokene"})
    void aRedirectUriEqualToTheDerivedOneIsAccepted(String redirectUri) {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, REDIRECT + "=" + redirectUri)
                .run(context -> assertThat(dokeneRegistration(context).getRedirectUri())
                        .isEqualTo(ORIGIN + "/login/oauth2/code/{registrationId}"));
    }

    /** covers: AC-5. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{baseUrl}/login/oauth2/code/{registrationId}",
            "{baseScheme}://{baseHost}{basePort}/login/oauth2/code/dokene",
            "https://attacker.example/login/oauth2/code/dokene",
            "http://localhost:9090/login/oauth2/code/dokene",
            ORIGIN + "/login/oauth2/code/other",
            ""
    })
    void anyOtherRedirectUriStopsStartupAndSaysToRemoveIt(String redirectUri) {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, REDIRECT + "=" + redirectUri)
                .run(context -> assertLeftoverRedirectRefused(context));
    }

    /** covers: AC-5. The bound properties see every relaxed spelling, so each one is refused the same way. */
    @Test
    void theLeftoverRedirectUriIsRefusedInEverySpelling() {
        String leftover = "{baseUrl}/login/oauth2/code/{registrationId}";
        WebApplicationContextRunner configured = withRegistration
                .withPropertyValues("dokene.security.public-origin=" + ORIGIN);

        configured.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(REDIRECT_ENV, leftover))))
                .run(RedirectOriginStartupTest::assertLeftoverRedirectRefused);
        configured.withSystemProperties(REDIRECT + "=" + leftover)
                .run(RedirectOriginStartupTest::assertLeftoverRedirectRefused);
        configured.withPropertyValues("spring.security.oauth2.client.registration.dokene.redirectUri=" + leftover)
                .run(RedirectOriginStartupTest::assertLeftoverRedirectRefused);
        configured.withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("json", Map.of(
                            "spring.application.json",
                            "{\"spring\":{\"security\":{\"oauth2\":{\"client\":{\"registration\":{\"dokene\":"
                                    + "{\"redirect-uri\":\"" + leftover + "\"}}}}}}}")));
                    new SpringApplicationJsonEnvironmentPostProcessor()
                            .postProcessEnvironment(context.getEnvironment(), null);
                })
                .run(RedirectOriginStartupTest::assertLeftoverRedirectRefused);
    }

    /** covers: AC-5. */
    @Test
    void aSecondRegistrationIdStopsStartup() {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN,
                        "spring.security.oauth2.client.registration.other.provider=dokene",
                        "spring.security.oauth2.client.registration.other.client-id=other",
                        "spring.security.oauth2.client.registration.other.authorization-grant-type=authorization_code")
                .run(context -> assertStartupRefused(context, "other", "only the \"dokene\" client registration"));
    }

    /** covers: AC-5. Only another id is configured, so the "dokene" registration is missing. */
    @Test
    void aRegistrationUnderAnotherIdAloneStopsStartup() {
        withoutRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN,
                        "spring.security.oauth2.client.registration.other.provider=other",
                        "spring.security.oauth2.client.registration.other.client-id=other",
                        "spring.security.oauth2.client.registration.other.authorization-grant-type=authorization_code",
                        "spring.security.oauth2.client.registration.other.redirect-uri=" + ORIGIN + "/cb",
                        "spring.security.oauth2.client.provider.other.authorization-uri=http://idp.example.test/a",
                        "spring.security.oauth2.client.provider.other.token-uri=http://idp.example.test/t")
                .run(context -> assertStartupRefused(context, "other", "only the \"dokene\" client registration"));
    }

    /** covers: AC-5. The callback path is fixed, so under a context path it would miss the application. */
    @Test
    void aServletContextPathStopsStartupWithARegistration() {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN,
                        "server.servlet.context-path=/dokene")
                .run(context -> assertStartupRefused(context, "SERVER_SERVLET_CONTEXT_PATH"));
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, "server.servlet.context-path=/")
                .run(context -> assertThat(context).hasNotFailed());
        withoutRegistration.withPropertyValues("server.servlet.context-path=/dokene")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** covers: AC-3. An empty override yields "", not the default; Spring would replay to a relative path. */
    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "/logout/connect/back-channel/{registrationId}",
            "localhost:8080/logout/connect/back-channel/{registrationId}",
            "ftp://localhost/logout/connect/back-channel/{registrationId}",
            "http:///logout/connect/back-channel/{registrationId}",
            "http://user@dokene_backend/logout/connect/back-channel/{registrationId}",
            "http://dokene_backend:port/logout/connect/back-channel/{registrationId}",
            "http://local host/logout/connect/back-channel/{registrationId}"
    })
    void aBlankOrNonAbsoluteReplayUriStopsStartupWithOrWithoutARegistration(String replayUri) {
        withoutRegistration.withPropertyValues("dokene.security.oidc.back-channel-logout-uri=" + replayUri)
                .run(context -> assertStartupRefused(context, "DOKENE_BACK_CHANNEL_LOGOUT_URI"));
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN,
                        "dokene.security.oidc.back-channel-logout-uri=" + replayUri)
                .run(context -> assertStartupRefused(context, "DOKENE_BACK_CHANNEL_LOGOUT_URI"));
    }

    /**
     * covers: AC-1, AC-5. Only Boot's own repository gets the derived callback, so any other repository would serve
     * registrations whose redirect URI nothing has checked.
     */
    @Test
    void aClientRegistrationRepositoryThatIsNotBootsOwnStopsStartup() {
        withoutRegistration.withUserConfiguration(CustomRepositoryConfiguration.class)
                .withPropertyValues("dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertStartupRefused(context, "ClientRegistrationRepository", "customRepository"));
    }

    /** covers: AC-1, AC-5. Boot's bean backs off for a repository of the same class, which the type alone lets by. */
    @Test
    void anInMemoryRepositoryHoldingAnotherRegistrationStopsStartup() {
        withRegistration.withUserConfiguration(SecondRegistrationRepositoryConfiguration.class)
                .withPropertyValues("dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertStartupRefused(context, "ClientRegistrationRepository", "twoRegistrations",
                        "\"dokene\""));
    }

    /**
     * covers: AC-1, AC-5. A repository built while the post processors are still being created skips the rewrite and
     * keeps Boot's {baseUrl} template, which reads the request; Spring only logs an INFO line about that.
     */
    @Test
    void aRepositoryBuiltBeforeThePostProcessorIsRegisteredStopsStartup() {
        withRegistration.withUserConfiguration(EarlyRepositoryConfiguration.class)
                .withPropertyValues("dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertStartupRefused(context, "login callback", "DOKENE_PUBLIC_ORIGIN",
                        "before"));
    }

    /** covers: AC-3. The default as application.yml writes it, the copy the shipped backend reads. */
    @Test
    void theReplayUriDefaultsToLoopbackOnTheListenPort() {
        withoutReplayUri.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(stubClientRegistration())
                .withPropertyValues("dokene.security.public-origin=" + ORIGIN, "server.port=18443")
                .run(context -> assertThat(context.getBean(RedirectOriginStartupValidator.class)
                        .backChannelLogoutUri())
                        .isEqualTo("http://localhost:18443/logout/connect/back-channel/{registrationId}"));
    }

    /** covers: AC-3. The default is written once, in application.yml; no second copy fills in for a missing one. */
    @Test
    void aMissingReplayUriPropertyStopsStartup() {
        withoutReplayUri.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining(REPLAY);
        });
    }

    /**
     * covers: AC-3. Port 0 asks the server for any free port, so the replay URI built from server.port would point
     * at no listener and every provider logout would be lost without an error.
     */
    @Test
    void aReplayUriOnPortZeroStopsStartupWithARegistrationOnly() {
        String onPortZero = REPLAY + "=http://localhost:0/logout/connect/back-channel/{registrationId}";

        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, onPortZero)
                .run(context -> assertStartupRefused(context, "DOKENE_BACK_CHANNEL_LOGOUT_URI", "port 0",
                        "SERVER_PORT"));
        withoutRegistration.withPropertyValues(onPortZero).run(context -> assertThat(context).hasNotFailed());
    }

    /** covers: AC-3. java.net.URI gives such a name no host and no port, so the port has to be read another way. */
    @Test
    void aReplayUriOnPortZeroStopsStartupWhenTheHostNameHasAnUnderscore() {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN,
                        REPLAY + "=http://dokene_backend:0/logout/connect/back-channel/{registrationId}")
                .run(context -> assertStartupRefused(context, "DOKENE_BACK_CHANNEL_LOGOUT_URI", "port 0"));
    }

    /** covers: AC-3. A TLS listener does not answer the plain http replay the default sends. */
    @Test
    void aPlainHttpReplayUriStopsStartupWhenTheListenerUsesTls() {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, "server.ssl.bundle=web")
                .run(context -> assertStartupRefused(context, "DOKENE_BACK_CHANNEL_LOGOUT_URI", "server.ssl",
                        "https://", "certificate"));
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, "server.ssl.bundle=web",
                        "server.ssl.enabled=false")
                .run(context -> assertThat(context).hasNotFailed());
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, "server.ssl.bundle=web",
                        "server.port=8443",
                        REPLAY + "=https://localhost:8443/logout/connect/back-channel/{registrationId}")
                .run(context -> assertThat(context).hasNotFailed());
        withoutRegistration.withPropertyValues("server.ssl.bundle=web")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** covers: AC-3. An operator override without a request derived placeholder is kept. */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://backend.internal:9000/logout/connect/back-channel/{registrationId}",
            "http://localhost:8080/internal/logout/connect/back-channel/dokene",
            "http://127.0.0.1:8080/logout/connect/back-channel/{registrationId}",
            "http://[::1]:8080/logout/connect/back-channel/{registrationId}",
            "http://dokene_backend:9000/logout/connect/back-channel/{registrationId}",
            "http://dokene_backend/logout/connect/back-channel/{registrationId}"
    })
    void aFixedReplayUriOverrideIsKept(String replayUri) {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, REPLAY + "=" + replayUri)
                .run(context -> assertThat(context.getBean(RedirectOriginStartupValidator.class)
                        .backChannelLogoutUri())
                        .isEqualTo(replayUri));
    }

    /**
     * covers: AC-3. A loopback replay on another port than the server listens on reaches no listener, like port 0:
     * a port left out is 80 or 443, and an override can outlive a changed SERVER_PORT.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/logout/connect/back-channel/{registrationId}",
            "http://localhost:9000/logout/connect/back-channel/{registrationId}",
            "http://LOCALHOST:9000/logout/connect/back-channel/{registrationId}",
            "http://127.0.0.1:9000/logout/connect/back-channel/{registrationId}",
            "http://[::1]:9000/logout/connect/back-channel/{registrationId}",
            "https://localhost/logout/connect/back-channel/{registrationId}",
            "http://127.0.0.2:9000/logout/connect/back-channel/{registrationId}",
            "http://localhost.:9000/logout/connect/back-channel/{registrationId}",
            "http://backend.localhost:9000/logout/connect/back-channel/{registrationId}",
            "http://[0:0:0:0:0:0:0:1]:9000/logout/connect/back-channel/{registrationId}"
    })
    void aLoopbackReplayUriOnAnotherPortThanTheListenerStopsStartupWithARegistrationOnly(String replayUri) {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, REPLAY + "=" + replayUri)
                .run(context -> assertStartupRefused(context, "DOKENE_BACK_CHANNEL_LOGOUT_URI", "SERVER_PORT",
                        "8080"));
        withoutRegistration.withPropertyValues(REPLAY + "=" + replayUri)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** covers: AC-3. The comparison follows the configured port, also when it is 80 and the URI leaves it out. */
    @Test
    void aLoopbackReplayUriOnTheListenPortStartsWhenThePortIsLeftOut() {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, "server.port=80",
                        REPLAY + "=http://localhost/logout/connect/back-channel/{registrationId}")
                .run(context -> assertThat(context).hasNotFailed());
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, "server.port=9000",
                        REPLAY + "=http://localhost:9000/logout/connect/back-channel/{registrationId}")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * covers: AC-3. server.port=-1 keeps the web context with no listener, so nothing is received or replayed; the
     * default then reads http://localhost:-1, which must not stop startup with a message about a setting left alone.
     */
    @Test
    void theReplayUriIsNotCheckedWhenTheServerHasNoListener() {
        WebApplicationContextRunner noListener = withoutReplayUri
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("server.port=-1");

        noListener.run(context -> assertThat(context).hasNotFailed());
        noListener.withPropertyValues(stubClientRegistration())
                .withPropertyValues("dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** covers: AC-3. Only {registrationId} is filled without the request, so every other placeholder is refused. */
    @ParameterizedTest
    @ValueSource(strings = {"{baseUrl}", "{baseScheme}", "{baseHost}", "{basePort}", "{basePath}", "{basePort:\\d*}",
            "{other}", "{", "}"})
    void anyOtherPlaceholderInTheReplayUriStopsStartup(String placeholder) {
        String replayUri = REPLAY + "=http://localhost" + placeholder + "/logout/connect/back-channel/{registrationId}";

        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN, replayUri)
                .run(context -> assertStartupRefused(context, "DOKENE_BACK_CHANNEL_LOGOUT_URI", "placeholder",
                        "{registrationId}"));
        withoutRegistration.withPropertyValues(replayUri)
                .run(context -> assertStartupRefused(context, "DOKENE_BACK_CHANNEL_LOGOUT_URI", "placeholder"));
    }

    /** covers: AC-7. */
    @ParameterizedTest
    @ValueSource(strings = {"none", "framework", "NONE", "Framework"})
    void theNoneAndFrameworkStrategiesStart(String strategy) {
        withoutRegistration.withPropertyValues("server.forward-headers-strategy=" + strategy)
                .run(context -> assertThat(context).hasNotFailed());
        withRegistration.withPropertyValues("server.forward-headers-strategy=" + strategy,
                        "dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** covers: AC-7. Unset or blank would let Boot pick native on a detected cloud platform. */
    @ParameterizedTest
    @ValueSource(strings = {"native", "NATIVE", ""})
    void anyOtherStrategyStopsStartupWithOrWithoutARegistration(String strategy) {
        withoutRegistration.withPropertyValues("server.forward-headers-strategy=" + strategy)
                .run(context -> assertStartupRefused(context, "SERVER_FORWARD_HEADERS_STRATEGY"));
        withRegistration.withPropertyValues("server.forward-headers-strategy=" + strategy,
                        "dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertStartupRefused(context, "SERVER_FORWARD_HEADERS_STRATEGY"));
    }

    /** covers: AC-7. */
    @Test
    void anUnsetStrategyStopsStartup() {
        new WebApplicationContextRunner()
                .withUserConfiguration(ServerPropertiesConfiguration.class, RedirectOriginConfiguration.class)
                .run(context -> assertStartupRefused(context, "SERVER_FORWARD_HEADERS_STRATEGY"));
    }

    /** covers: AC-8. */
    @Test
    void logsTheOriginAndTheHstsResultOnceAtStartup(CapturedOutput output) {
        withRegistration.withPropertyValues("dokene.security.public-origin=" + ORIGIN)
                .run(context -> assertThat(context).hasNotFailed());
        withRegistration.withPropertyValues("dokene.security.public-origin=https://dokene.example")
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(output.getAll().lines().filter(line -> line.contains("OAuth public origin")))
                .hasSize(2)
                .allMatch(line -> line.contains("INFO"))
                .anyMatch(line -> line.contains(ORIGIN) && line.contains("HSTS off"))
                .anyMatch(line -> line.contains("https://dokene.example") && line.contains("HSTS on"));
    }

    /** The "dokene" registration against the in process provider, with no origin and no redirect URI. */
    private static String[] stubClientRegistration() {
        List<String> pairs = new ArrayList<>();
        try {
            OidcProviderStub.registerClient((name, value) -> pairs.add(name + "=" + value.get()));
        } catch (Exception exception) {
            throw new IllegalStateException("The OIDC provider stub did not start", exception);
        }
        return pairs.toArray(String[]::new);
    }

    private static ClientRegistration dokeneRegistration(AssertableWebApplicationContext context) {
        assertThat(context).hasNotFailed();
        return context.getBean(ClientRegistrationRepository.class).findByRegistrationId("dokene");
    }

    private static void assertLeftoverRedirectRefused(AssertableWebApplicationContext context) {
        assertStartupRefused(context, "remove", "DOKENE_PUBLIC_ORIGIN");
    }

    private static void assertStartupRefused(AssertableWebApplicationContext context, String... messageParts) {
        assertThat(context).hasFailed();
        Throwable cause = context.getStartupFailure();
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(IllegalStateException.class);
        for (String part : messageParts) {
            assertThat(cause.getMessage()).contains(part);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRepositoryConfiguration {

        @Bean
        ClientRegistrationRepository customRepository() {
            return registrationId -> null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class EarlyRepositoryConfiguration {

        /** Depending on the repository makes Spring build it before the post processors are registered. */
        @Bean
        static BeanPostProcessor needsTheRepositoryEarly(ClientRegistrationRepository repository) {
            return new BeanPostProcessor() {
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class DokeneOnlyRepositoryConfiguration {

        @Bean
        ClientRegistrationRepository dokeneOnly() {
            return new InMemoryClientRegistrationRepository(
                    SecondRegistrationRepositoryConfiguration.registration("dokene"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SecondRegistrationRepositoryConfiguration {

        @Bean
        ClientRegistrationRepository twoRegistrations() {
            return new InMemoryClientRegistrationRepository(registration("dokene"), registration("other"));
        }

        private static ClientRegistration registration(String id) {
            return ClientRegistration.withRegistrationId(id)
                    .clientId("client")
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri("http://localhost:1/authorize")
                    .tokenUri("http://localhost:1/token")
                    .build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ServerProperties.class)
    static class ServerPropertiesConfiguration {
    }
}
