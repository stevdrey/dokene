package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.identity.application.OidcIdentityResolver;
import io.github.stevdrey.dokene.identity.security.InternalOidcUserService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
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
            AuditRecorder auditRecorder
    ) {
        return new TenantContextRequestFilter(
                tenantContexts,
                tenantContextResolver,
                identityResolver,
                tenantScopedRequestMatcher,
                auditRecorder
        );
    }

    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(OidcIdentityResolver identityResolver) {
        return new InternalOidcUserService(identityResolver);
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
                "Content-Type", "Idempotency-Key", "If-Match", "X-CSRF-TOKEN", "X-Tenant-Id"
        ));
        configuration.setExposedHeaders(List.of("ETag"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            TenantContextRequestFilter tenantContextRequestFilter,
            OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            @Value("${dokene.security.post-login-redirect-url:/}") String postLoginRedirectUrl,
            @Value("${dokene.security.post-login-failure-redirect-url:}") String postLoginFailureRedirectUrl
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
                .addFilterAfter(tenantContextRequestFilter, AnonymousAuthenticationFilter.class);

        if (clientRegistrations.getIfAvailable() != null) {
            String failureRedirectUrl = resolvePostLoginFailureRedirectUrl(postLoginFailureRedirectUrl, postLoginRedirectUrl);
            http.oauth2Login(oauth -> oauth
                    .loginPage("/oauth2/authorization/dokene")
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                    .defaultSuccessUrl(postLoginRedirectUrl, true)
                    .failureHandler(createAuthenticationFailureHandler(failureRedirectUrl)));
        }
        return http.build();
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

