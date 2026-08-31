package io.github.stevdrey.dokene.tenant.application;

/**
 * Port for receiving notification of denied authorization decisions.
 */
@FunctionalInterface
public interface AuthorizationAuditListener {

    void onAuthorizationDenied(AuthorizationDeniedEvent event);

    static AuthorizationAuditListener noop() {
        return event -> { };
    }
}
