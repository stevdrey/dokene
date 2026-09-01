package io.github.stevdrey.dokene.tenant.application;

import org.springframework.security.access.AccessDeniedException;

/**
 * Thrown when an operation is denied due to missing context, inactive membership,
 * insufficient role permission, or cross-tenant resource mismatch.
 */
public class TenantAccessDeniedException extends AccessDeniedException {

    private static final String DEFAULT_MESSAGE = "Access denied";

    public TenantAccessDeniedException() {
        super(DEFAULT_MESSAGE);
    }

    public TenantAccessDeniedException(String message) {
        super(message == null || message.isBlank() ? DEFAULT_MESSAGE : message);
    }

    public TenantAccessDeniedException(String message, Throwable cause) {
        super(message == null || message.isBlank() ? DEFAULT_MESSAGE : message, cause);
    }
}
