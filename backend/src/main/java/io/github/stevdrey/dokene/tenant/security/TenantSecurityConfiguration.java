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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

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
            ObjectProvider<ClientRegistrationRepository> clientRegistrations
    )
            throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/oauth2/**", "/login/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/session").authenticated()
                        .anyRequest().authenticated())
                .cors(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.pathPattern("/api/**")
                ))
                .logout(logout -> logout
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .addFilterAfter(tenantContextRequestFilter, AnonymousAuthenticationFilter.class);

        if (clientRegistrations.getIfAvailable() != null) {
            http.oauth2Login(oauth -> oauth
                    .userInfoEndpoint(userInfo -> userInfo.oidcUserService(oidcUserService))
                    .defaultSuccessUrl("/api/session", true));
        }
        return http.build();
    }
}
