// ABOUTME: Pins which failures a Phase 3 last-resort handler answers as INTERNAL_ERROR (issue 293) and which it rethrows
// ABOUTME: so Spring, the outage boundary or a committed response keep their own outcome; the log carries no message.
package io.github.stevdrey.dokene.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.server.ResponseStatusException;

class UnexpectedFailuresTest {
    private static final String CANARY = "CANARY-SECRET-MESSAGE select * from secret_table";

    @Test
    void ordinaryRuntimeAndDataAccessFailuresAreUnexpected() {
        assertThat(UnexpectedFailures.isUnexpected(new IllegalStateException("x"))).isTrue();
        assertThat(UnexpectedFailures.isUnexpected(new PermissionDeniedDataAccessException("x", null))).isTrue();
        assertThat(UnexpectedFailures.isUnexpected(new NullPointerException())).isTrue();
    }

    @Test
    void failuresSpringOrItsFiltersAnswerAreNot() {
        assertThat(UnexpectedFailures.isUnexpected(new HttpRequestMethodNotSupportedException("DELETE"))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new ResponseStatusException(HttpStatus.NOT_FOUND))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new ServletException("x"))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new AccessDeniedException("x"))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new BadCredentialsException("x"))).isFalse();
        // Type conversion, binding and an unwritable response are answered by Spring's default resolver.
        assertThat(UnexpectedFailures.isUnexpected(new TypeMismatchException("x", Integer.class))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new BindException(new Object(), "target"))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new HttpMessageNotWritableException("x"))).isFalse();
        // A client that went away mid-response is not a server failure.
        assertThat(UnexpectedFailures.isUnexpected(new AsyncRequestNotUsableException("gone", new IOException()))).isFalse();
    }

    @Test
    void claimAnswersOnlyAnUnexpectedFailureOnAnUncommittedResponseThatIsNotAnOutage() throws Exception {
        MockHttpServletResponse open = new MockHttpServletResponse();
        assertThat(UnexpectedFailures.claim(new IllegalStateException("x"), open, failure -> false)).isTrue();
        assertThat(UnexpectedFailures.claim(new IllegalStateException("x"), open, failure -> true)).isFalse();
        assertThat(UnexpectedFailures.claim(new HttpMessageNotWritableException("x"), open, failure -> false))
                .isFalse();

        MockHttpServletResponse committed = new MockHttpServletResponse();
        committed.getWriter().write("partial");
        committed.flushBuffer();
        assertThat(UnexpectedFailures.claim(new IllegalStateException("x"), committed, failure -> false)).isFalse();
    }

    @Test
    void theLogCarriesClassesAndFramesButNeverAMessageAndSurvivesACyclicChain() {
        Logger logger = (Logger) LoggerFactory.getLogger(UnexpectedFailures.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            IllegalStateException first = new IllegalStateException(CANARY);
            IllegalArgumentException second = new IllegalArgumentException(CANARY, first);
            first.initCause(second);

            UnexpectedFailures.log(first);

            String logged = appender.list.getFirst().getFormattedMessage();
            assertThat(logged).contains("exceptionClass=java.lang.IllegalStateException",
                    "java.lang.IllegalArgumentException", "at io.github.stevdrey.dokene.http.UnexpectedFailuresTest");
            assertThat(logged).doesNotContain("CANARY", "secret_table");
            assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        } finally {
            logger.detachAppender(appender);
        }
    }
}
