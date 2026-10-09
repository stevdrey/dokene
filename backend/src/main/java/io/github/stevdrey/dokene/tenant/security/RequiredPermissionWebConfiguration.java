package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class RequiredPermissionWebConfiguration implements WebMvcConfigurer {
    private final TenantAuthorizationService authorization;

    RequiredPermissionWebConfiguration(TenantAuthorizationService authorization) {
        this.authorization = authorization;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequiredPermissionInterceptor(authorization));
    }
}
