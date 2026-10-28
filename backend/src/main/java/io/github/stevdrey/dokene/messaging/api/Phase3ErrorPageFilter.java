// ABOUTME: On the servlet error dispatch, turns a 500 for a Phase 3 route into the closed INTERNAL_ERROR problem
// ABOUTME: (issue 293), so a filter failure before any controller advice never leaves through Spring's default body.
package io.github.stevdrey.dokene.messaging.api;

import io.github.stevdrey.dokene.http.UnexpectedFailures;
import io.github.stevdrey.dokene.messaging.api.MessagingApiContract.MessagingProblem;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controller advices only see failures raised inside a controller. A failure in a filter (tenant resolution, a
 * security filter) reaches the container, which dispatches to {@code /error}; this filter runs on that dispatch and
 * answers before Spring Boot's error controller, with the same response headers that controller's page would get.
 * It acts only on a {@code 500} for a Phase 3 path that is not yet committed: other routes, other statuses and the
 * empty {@code 503} of the database-outage and audit boundaries (written on the request dispatch itself) are untouched.
 */
public class Phase3ErrorPageFilter extends OncePerRequestFilter {
    private static final List<PathPattern> PHASE3_PATHS = List.of(
            "/api/messages/**", "/api/messaging/**", "/api/templates/**", "/api/integrations/**",
            "/api/customers/*/messages").stream().map(PathPatternParser.defaultInstance::parse).toList();

    private final JsonMapper json;

    public Phase3ErrorPageFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getDispatcherType() != DispatcherType.ERROR;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isPhase3InternalError(request) || response.isCommitted()) {
            chain.doFilter(request, response);
            return;
        }
        if (request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure) {
            UnexpectedFailures.log(failure);
        }
        MessagingProblem body = MessagingProblem.of(MessagingErrorCode.INTERNAL_ERROR);
        response.setStatus(body.status());
        response.setContentType(MessagingApiContract.PROBLEM_MEDIA_TYPE);
        response.getOutputStream().write(json.writeValueAsBytes(body));
    }

    static boolean isPhase3Path(String uri) {
        PathContainer path = PathContainer.parsePath(uri);
        return PHASE3_PATHS.stream().anyMatch(pattern -> pattern.matches(path));
    }

    private static boolean isPhase3InternalError(HttpServletRequest request) {
        return request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer status
                && status == HttpServletResponse.SC_INTERNAL_SERVER_ERROR
                && request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri
                && isPhase3Path(uri);
    }
}
