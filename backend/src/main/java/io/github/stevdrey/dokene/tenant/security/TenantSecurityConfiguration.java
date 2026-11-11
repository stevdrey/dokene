package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.identity.application.OidcIdentityResolver;
import io.github.stevdrey.dokene.identity.security.BffSessionLifetimeFilter;
import io.github.stevdrey.dokene.identity.security.PreLoginSessionExpiryFilter;
import io.github.stevdrey.dokene.identity.security.BffSessionProperties;
import io.github.stevdrey.dokene.identity.security.InternalOidcUserService;
import io.github.stevdrey.dokene.identity.security.PreLoginAuthorizationRequestRepository;
import io.github.stevdrey.dokene.identity.security.SessionBoundCsrfTokenRepository;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import jakarta.servlet.http.HttpSession;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.client.RestClient;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties(BffSessionProperties.class)
class TenantSecurityConfiguration {

    @Bean
    TenantScopedRequestMatcher tenantScopedRequestMatcher() {
        return new TenantScopedRequestMatcher();
    }

    @Bean
    TenantContextRequestFilter tenantContextRequestFilter(
            TenantContextProvider tenantContexts,
            TenantContextResolver tenantContextResolver,
            AuthenticatedTenantIdentityResolver identityResolver,
            RequestMatcher tenantScopedRequestMatcher,
            AuditRecorder auditRecorder,
            ObjectProvider<TenantSelectionRejectionResponder> rejectionResponders
    ) {
        return new TenantContextRequestFilter(
                tenantContexts,
                tenantContextResolver,
                identityResolver,
                tenantScopedRequestMatcher,
                auditRecorder,
                rejectionResponders.orderedStream().toList()
        );
    }

    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(OidcIdentityResolver identityResolver) {
        return new InternalOidcUserService(identityResolver);
    }

    /** Provider tokens live in the session they belong to and are discarded with it (ADR 0007). */
    @Bean
    OAuth2AuthorizedClientRepository authorizedClientRepository() {
        return new HttpSessionOAuth2AuthorizedClientRepository();
    }

    @Bean
    BffSessionLifetimeFilter bffSessionLifetimeFilter(
            BffSessionProperties properties,
            OAuth2AuthorizedClientRepository authorizedClients,
            Clock clock
    ) {
        RestClientRefreshTokenTokenResponseClient refreshTokenClient = new RestClientRefreshTokenTokenResponseClient();
        refreshTokenClient.setRestClient(createTokenEndpointRestClient(properties.revalidationTimeout()));
        return new BffSessionLifetimeFilter(properties, authorizedClients, refreshTokenClient, clock);
    }

    /**
     * Spring Security's token endpoint client (same converters and OAuth2 error handling) with finite deadlines: a
     * revalidation holds the session's lock, so a stalled provider must turn into the grace path, not a hung request.
     */
    static RestClient createTokenEndpointRestClient(Duration timeout) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters
                        .addCustomConverter(new FormHttpMessageConverter())
                        .addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    /** Lets the OIDC session registry follow session id changes and forget destroyed sessions. */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${dokene.security.cors.allowed-origins:}") List<String> allowedOrigins
    ) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Content-Type", "Idempotency-Key", "If-Match", "If-None-Match", "X-CSRF-TOKEN", "X-Tenant-Id"
        ));
        // X-Request-Id is the server-generated correlation id; the SPA needs it to report problems to support.
        // Retry-After is not CORS-safelisted; the SPA needs it to honor 429 throttling and the database-outage 503.
        configuration.setExposedHeaders(List.of("ETag", "X-Request-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** BFF responses are JSON, redirects or plain errors: they never need to load or embed anything. */
    static final String API_CONTENT_SECURITY_POLICY =
            "default-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    /** Loopback on the port the provider called: never a host taken from the request. */
    static final String DEFAULT_BACK_CHANNEL_LOGOUT_URI =
            "{baseScheme}://localhost{basePort}/logout/connect/back-channel/{registrationId}";
    static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TenantContextRequestFilter tenantContextRequestFilter,
            BffSessionLifetimeFilter bffSessionLifetimeFilter,
            OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            BffSessionProperties sessionProperties,
            Clock clock,
            @Value("${dokene.security.post-login-redirect-url:/}") String postLoginRedirectUrl,
            @Value("${dokene.security.post-login-failure-redirect-url:}") String postLoginFailureRedirectUrl,
            @Value("${dokene.security.oidc.back-channel-logout-uri:" + DEFAULT_BACK_CHANNEL_LOGOUT_URI + "}")
            String backChannelLogoutUri
    )
            throws Exception {
        LogoutSuccessHandler logoutSuccessHandler = createLogoutSuccessHandler(clientRegistrations);

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/", "/oauth2/**", "/login/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/session").authenticated()
                        .anyRequest().authenticated())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                // An anonymous request must not get a session (issue 311): the post-login target is fixed, so a
                // stored request would never be read, and a CSRF token is only readable through a session.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .csrf(csrf -> csrf.csrfTokenRepository(new SessionBoundCsrfTokenRepository()))
                .cors(Customizer.withDefaults())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(API_CONTENT_SECURITY_POLICY))
                        .permissionsPolicyHeader(policy -> policy.policy(PERMISSIONS_POLICY))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.pathPattern("/api/**")
                        )
                        .accessDeniedHandler(createAccessDeniedHandler()))
                .logout(logout -> logout
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(logoutSuccessHandler))
                // Before the CSRF filter: it refuses an unsafe request itself, and a request this filter never sees
                // would keep an abandoned login start session alive (issue 311).
                .addFilterBefore(new PreLoginSessionExpiryFilter(sessionProperties.preLoginTimeout(), clock),
                        CsrfFilter.class)
                .addFilterBefore(bffSessionLifetimeFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(tenantContextRequestFilter, AnonymousAuthenticationFilter.class);

        if (clientRegistrations.getIfAvailable() != null) {
            String failureRedirectUrl = resolvePostLoginFailureRedirectUrl(postLoginFailureRedirectUrl, postLoginRedirectUrl);
            http.oauth2Login(oauth -> oauth
                    .loginPage("/oauth2/authorization/dokene")
                    // One repository serves the login start and the callback: the session a login start creates is
                    // short lived until the login succeeds (issue 311).
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestRepository(
                            new PreLoginAuthorizationRequestRepository(sessionProperties.preLoginTimeout(), clock)))
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                    .successHandler(createAuthenticationSuccessHandler(postLoginRedirectUrl, bffSessionLifetimeFilter))
                    .failureHandler(createAuthenticationFailureHandler(failureRedirectUrl)));
            // The provider's logout token ends the matching sessions: the handler replays the logout token to this
            // endpoint once per session, with that session's cookie, so the URI must reach this instance directly.
            http.oidcLogout(logout -> logout.backChannel(backChannel -> backChannel.logoutUri(backChannelLogoutUri)));
        }
        return http.build();
    }

    private AuthenticationSuccessHandler createAuthenticationSuccessHandler(
            String postLoginRedirectUrl,
            BffSessionLifetimeFilter bffSessionLifetimeFilter
    ) {
        SimpleUrlAuthenticationSuccessHandler redirect = new SimpleUrlAuthenticationSuccessHandler(postLoginRedirectUrl);
        redirect.setAlwaysUseDefaultTargetUrl(true);
        return (request, response, authentication) -> {
            // Runs after session fixation protection, so the clocks start on the authenticated session.
            HttpSession session = request.getSession();
            bffSessionLifetimeFilter.startSession(session);
            // changeSessionId() keeps the session object, so it still carries the pre login idle timeout: without
            // this reset every signed in session would idle out after the pre login timeout (issue 311).
            session.removeAttribute(BffSessionLifetimeFilter.PRE_LOGIN_STARTED_AT);
            session.setMaxInactiveInterval(request.getServletContext().getSessionTimeout() * 60);
            redirect.onAuthenticationSuccess(request, response, authentication);
        };
    }

    static String resolvePostLoginFailureRedirectUrl(String configuredFailureUrl, String postLoginRedirectUrl) {
        if (configuredFailureUrl != null && !configuredFailureUrl.isBlank()) {
            return configuredFailureUrl.trim();
        }
        String base = (postLoginRedirectUrl != null && !postLoginRedirectUrl.isBlank())
                ? postLoginRedirectUrl.trim()
                : "/";
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(base);
        if (builder.build().getPath() == null || builder.build().getPath().isEmpty()) {
            builder.path("/");
        }
        return builder
                .queryParam("error", "login_failed")
                .build()
                .toUriString();
    }

    private AuthenticationFailureHandler createAuthenticationFailureHandler(String failureRedirectUrl) {
        SimpleUrlAuthenticationFailureHandler failureHandler =
                new SimpleUrlAuthenticationFailureHandler(failureRedirectUrl);
        failureHandler.setAllowSessionCreation(false);
        CookieClearingLogoutHandler sessionCookieClearing = new CookieClearingLogoutHandler("JSESSIONID");
        return (request, response, exception) -> {
            // A failed login ends its login start session at once (issue 311). A signed in session is left alone: the
            // cookie is SameSite=Lax, so otherwise any cross-site link to the callback URL would sign the user out.
            HttpSession session = request.getSession(false);
            try {
                if (session != null && session.getAttribute(BffSessionLifetimeFilter.AUTHENTICATED_AT) == null) {
                    session.invalidate();
                    sessionCookieClearing.logout(request, response, null);
                }
            } catch (IllegalStateException alreadyInvalidated) {
                // A concurrent request ended this session first.
            }
            failureHandler.onAuthenticationFailure(request, response, exception);
        };
    }

    private LogoutSuccessHandler createLogoutSuccessHandler(
            ObjectProvider<ClientRegistrationRepository> clientRegistrations
    ) {
        HttpStatusReturningLogoutSuccessHandler apiLogoutSuccessHandler =
                new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT);
        ClientRegistrationRepository repository = clientRegistrations.getIfAvailable();
        if (repository == null) {
            return apiLogoutSuccessHandler;
        }

        OidcClientInitiatedLogoutSuccessHandler oidcLogoutSuccessHandler =
                new OidcClientInitiatedLogoutSuccessHandler(repository);
        oidcLogoutSuccessHandler.setPostLogoutRedirectUri("{baseUrl}/");

        return (request, response, authentication) -> {
            boolean providerRequested = "true".equalsIgnoreCase(request.getParameter("provider"));
            if (providerRequested && authentication instanceof OAuth2AuthenticationToken) {
                oidcLogoutSuccessHandler.onLogoutSuccess(request, response, authentication);
            } else {
                apiLogoutSuccessHandler.onLogoutSuccess(request, response, authentication);
            }
        };
    }

    private AccessDeniedHandler createAccessDeniedHandler() {
        RequestMatcher apiMatcher = PathPatternRequestMatcher.pathPattern("/api/**");
        AccessDeniedHandler defaultHandler = new AccessDeniedHandlerImpl();

        return (request, response, accessDeniedException) -> {
            if (apiMatcher.matches(request)) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication == null || !authentication.isAuthenticated() || (authentication instanceof AnonymousAuthenticationToken)) {
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    return;
                }
            }
            defaultHandler.handle(request, response, accessDeniedException);
        };
    }
}

