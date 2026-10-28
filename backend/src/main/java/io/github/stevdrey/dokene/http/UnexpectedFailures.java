// ABOUTME: Decides whether a failure that reached a route-family fallback handler is truly unexpected, and logs one
// ABOUTME: without its message (issue 293). Spring MVC, servlet and security failures keep their own answers.
package io.github.stevdrey.dokene.http;

import jakarta.servlet.ServletException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;

public final class UnexpectedFailures {
    private static final Logger log = LoggerFactory.getLogger(UnexpectedFailures.class);

    private UnexpectedFailures() {
    }

    /**
     * False for failures something else already answers: Spring MVC's own errors (405, 404, 406 and the like, which
     * implement {@link ErrorResponse}), other servlet errors, and Spring Security's access and authentication
     * failures, which its filters translate. A fallback handler rethrows those unchanged.
     */
    public static boolean isUnexpected(Throwable failure) {
        return !(failure instanceof ErrorResponse || failure instanceof ServletException
                || failure instanceof AccessDeniedException || failure instanceof AuthenticationException);
    }

    /**
     * Logs the failure with its class and the class of its innermost cause only. Messages are never logged: driver,
     * pool and framework messages can carry SQL, host names or identifiers. The MDC has the correlation id.
     */
    public static void log(Throwable failure) {
        log.error("Unexpected failure on a Phase 3 route: exceptionClass={} rootCauseClass={}",
                failure.getClass().getName(), rootCause(failure).getClass().getName());
    }

    private static Throwable rootCause(Throwable failure) {
        // Identity set: a cause chain can be cyclic, and equals() on exceptions is not to be trusted.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current.getCause() != null && seen.add(current)) {
            current = current.getCause();
        }
        return current;
    }
}
