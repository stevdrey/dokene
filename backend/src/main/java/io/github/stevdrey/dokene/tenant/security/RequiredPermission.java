package io.github.stevdrey.dokene.tenant.security;

import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the tenant permission a controller method requires. It is enforced by
 * {@link RequiredPermissionInterceptor} before request binding and validation, so an unauthorized caller
 * always receives 403 and an audited denial regardless of missing headers or malformed bodies.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiredPermission {
    TenantPermission value();
}
