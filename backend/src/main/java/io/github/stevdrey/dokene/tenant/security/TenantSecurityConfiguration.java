package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import org.springframework.security.web.util.matcher.RequestMatcher;

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
            RequestMatcher tenantScopedRequestMatcher
    ) {
        return new TenantContextRequestFilter(
                tenantContexts,
                tenantContextResolver,
                identityResolver,
                tenantScopedRequestMatcher
        );
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TenantContextRequestFilter tenantContextRequestFilter)
            throws Exception {
        return http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .addFilterAfter(tenantContextRequestFilter, AnonymousAuthenticationFilter.class)
                .build();
    }
}
