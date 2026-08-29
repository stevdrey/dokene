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

@Configuration
@ConditionalOnWebApplication(type = Type.SERVLET)
class TenantSecurityConfiguration {

    @Bean
    TenantContextRequestFilter tenantContextRequestFilter(
            TenantContextProvider tenantContexts,
            TenantContextResolver tenantContextResolver,
            AuthenticatedTenantIdentityResolver identityResolver
    ) {
        return new TenantContextRequestFilter(tenantContexts, tenantContextResolver, identityResolver);
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
