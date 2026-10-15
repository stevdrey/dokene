package io.github.stevdrey.dokene.tenant.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a module's closed RFC 9457 problem record for the routes it owns. The status always comes from the
 * rejection, so a module cannot change the 400/403 semantics of tenant selection (ADR 0023 §6).
 */
public abstract class ProblemJsonTenantSelectionResponder implements TenantSelectionRejectionResponder {

    static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RequestMatcher ownedRoutes;

    protected ProblemJsonTenantSelectionResponder(RequestMatcher ownedRoutes) {
        this.ownedRoutes = Objects.requireNonNull(ownedRoutes, "Owned routes matcher is required");
    }

    /** The closed problem body for the rejection; its {@code status} member must equal {@code rejection.status()}. */
    protected abstract Record problem(TenantSelectionRejection rejection);

    @Override
    public final boolean handles(HttpServletRequest request) {
        return ownedRoutes.matches(request);
    }

    @Override
    public final void write(HttpServletResponse response, TenantSelectionRejection rejection) throws IOException {
        byte[] body = JSON.writeValueAsBytes(problem(rejection));
        response.setStatus(rejection.status());
        response.setContentType(PROBLEM_MEDIA_TYPE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
