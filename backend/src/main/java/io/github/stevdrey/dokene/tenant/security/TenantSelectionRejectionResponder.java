package io.github.stevdrey.dokene.tenant.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Lets the module that owns a route supply the body of a tenant-selection rejection, so the tenant module never
 * depends on that module's error contract. Routes no responder handles keep the empty-body error.
 */
public interface TenantSelectionRejectionResponder {

    /** Claims only the routes the implementing module owns. */
    boolean handles(HttpServletRequest request);

    /** Writes the complete rejection with {@code rejection.status()}; the audit has already been recorded. */
    void write(HttpServletResponse response, TenantSelectionRejection rejection) throws IOException;
}
