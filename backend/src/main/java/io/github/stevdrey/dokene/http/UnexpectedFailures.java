// ABOUTME: Decides whether a failure reaching a Phase 3 last-resort handler is answered as INTERNAL_ERROR, and logs it
// ABOUTME: with classes and stack frames but never a message (issue 293). Spring keeps answering its own errors.
package io.github.stevdrey.dokene.http;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

public final class UnexpectedFailures {
    private static final Logger log = LoggerFactory.getLogger(UnexpectedFailures.class);
    private static final int MAX_CAUSES = 5;
    private static final int MAX_FRAMES = 12;

    private UnexpectedFailures() {
    }

    /**
     * The last-resort decision shared by the message, template and integration advices: true when {@code failure} is
     * an unexpected failure the caller should answer as the closed {@code 500 INTERNAL_ERROR} problem (the failure is
     * then logged), false when the caller must rethrow it unchanged. Rethrown are a database outage ({@code outage},
     * answered by the ADR 0029 boundary), failures Spring answers itself (see {@link #isUnexpected}) and any failure
     * after the response was committed, when no body can be written any more. Audit failures never get here: their
     * advice is ordered first.
     */
    public static boolean claim(Exception failure, HttpServletResponse response, Predicate<Throwable> outage) {
        if (!isUnexpected(failure) || response.isCommitted() || outage.test(failure)) {
            return false;
        }
        log(failure);
        return true;
    }

    /**
     * False for failures something else already answers: Spring MVC's own errors (405, 404, 406 and the like, which
     * implement {@link ErrorResponse}), type-conversion and binding errors and an unwritable response (which Spring's
     * default resolver answers), a client that went away mid-response, other servlet errors, and Spring Security's
     * access and authentication failures, which its filters translate.
     */
    public static boolean isUnexpected(Throwable failure) {
        return !(failure instanceof ErrorResponse || failure instanceof ServletException
                || failure instanceof TypeMismatchException || failure instanceof BindException
                || failure instanceof HttpMessageNotWritableException
                || failure instanceof AsyncRequestNotUsableException
                || failure instanceof AccessDeniedException || failure instanceof AuthenticationException);
    }

    /**
     * Logs the failure with its class, the class of its innermost cause and the stack frames of each cause. Messages
     * are never logged: driver, pool and framework messages can carry SQL, host names or identifiers, and a frame
     * carries only class, method, file and line. The MDC has the correlation id.
     */
    public static void log(Throwable failure) {
        List<Throwable> chain = causes(failure);
        StringBuilder frames = new StringBuilder();
        for (Throwable cause : chain.subList(0, Math.min(chain.size(), MAX_CAUSES))) {
            frames.append(System.lineSeparator()).append("  ").append(cause.getClass().getName());
            StackTraceElement[] trace = cause.getStackTrace();
            for (int index = 0; index < Math.min(trace.length, MAX_FRAMES); index++) {
                frames.append(System.lineSeparator()).append("    at ").append(trace[index]);
            }
        }
        log.error("Unexpected failure on a Phase 3 route: exceptionClass={} rootCauseClass={}{}",
                failure.getClass().getName(), chain.getLast().getClass().getName(), frames);
    }

    private static List<Throwable> causes(Throwable failure) {
        // Identity set: a cause chain can be cyclic, and equals() on exceptions is not to be trusted.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }
}
