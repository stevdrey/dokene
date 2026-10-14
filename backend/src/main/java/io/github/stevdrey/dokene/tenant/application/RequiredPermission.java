package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the tenant permission a controller method requires. It is enforced by
 * {@code RequiredPermissionInterceptor} before request binding and validation, so an unauthorized caller
 * always receives 403 and an audited denial regardless of missing headers or malformed bodies.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiredPermission {
    TenantPermission[] value();

    /**
     * When true, holding any one of {@link #value()} admits the request; a caller holding none is denied, with one
     * audited denial per listed permission. For routes whose exact permission depends on resource state, which the service
     * then enforces (ADR 0023 §3.1, message cancellation). Defaults to requiring every listed permission.
     */
    boolean anyOf() default false;
}
