package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.identity.application.OidcIdentityResolver;
import io.github.stevdrey.dokene.identity.security.BffSessionLifetimeFilter;
import io.github.stevdrey.dokene.identity.security.BffSessionProperties;
import io.github.stevdrey.dokene.identity.security.InternalOidcUserService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import java.time.Clock;
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
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
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
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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
        return new BffSessionLifetimeFilter(
                properties, authorizedClients, new RestClientRefreshTokenTokenResponseClient(), clock);
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
                .addFilterBefore(bffSessionLifetimeFilter, AnonymousAuthenticationFilter.class)
                .addFilterAfter(tenantContextRequestFilter, AnonymousAuthenticationFilter.class);

        if (clientRegistrations.getIfAvailable() != null) {
            String failureRedirectUrl = resolvePostLoginFailureRedirectUrl(postLoginFailureRedirectUrl, postLoginRedirectUrl);
            http.oauth2Login(oauth -> oauth
                    .loginPage("/oauth2/authorization/dokene")
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
            bffSessionLifetimeFilter.startSession(request.getSession());
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
        return failureHandler;
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

