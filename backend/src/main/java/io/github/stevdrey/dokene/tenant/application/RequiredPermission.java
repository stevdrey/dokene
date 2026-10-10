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
     * When true, the caller needs at least one of the listed permissions instead of all of them. A caller holding
     * none is denied and audited for the first listed permission; the service is expected to re check the exact
     * permission once the resource state is known (for example cancel: MESSAGE_DRAFT from PENDING_APPROVAL,
     * MESSAGE_APPROVE from APPROVED).
     */
    boolean anyOf() default false;
}
