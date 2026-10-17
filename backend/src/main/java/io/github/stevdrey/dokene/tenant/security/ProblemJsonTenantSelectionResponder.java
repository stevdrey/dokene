package io.github.stevdrey.dokene.tenant.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a module's closed RFC 9457 problem record for the routes it owns. The status always comes from the
 * rejection, so a module cannot change the 400/403 semantics of tenant selection (ADR 0023 §6); a body whose own
 * {@code status} disagrees is refused when the bean is created, never served.
 */
public abstract class ProblemJsonTenantSelectionResponder implements TenantSelectionRejectionResponder {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RequestMatcher ownedRoutes;
    private final Map<TenantSelectionRejection, byte[]> bodies = new EnumMap<>(TenantSelectionRejection.class);

    /** Serializes the closed body of every rejection once; each body's status must equal the rejection's. */
    protected <P> ProblemJsonTenantSelectionResponder(
            RequestMatcher ownedRoutes,
            Function<TenantSelectionRejection, P> problems,
            ToIntFunction<P> statusOf
    ) {
        this.ownedRoutes = Objects.requireNonNull(ownedRoutes, "Owned routes matcher is required");
        Objects.requireNonNull(problems, "Problem mapping is required");
        Objects.requireNonNull(statusOf, "Problem status accessor is required");
        for (TenantSelectionRejection rejection : TenantSelectionRejection.values()) {
            P problem = Objects.requireNonNull(problems.apply(rejection), "Problem body is required");
            if (statusOf.applyAsInt(problem) != rejection.status()) {
                throw new IllegalStateException("Problem status for " + rejection + " must be " + rejection.status());
            }
            bodies.put(rejection, JSON.writeValueAsBytes(problem));
        }
    }

    @Override
    public final boolean handles(HttpServletRequest request) {
        return ownedRoutes.matches(request);
    }

    @Override
    public final void write(HttpServletResponse response, TenantSelectionRejection rejection) throws IOException {
        byte[] body = bodies.get(rejection);
        response.setStatus(rejection.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
