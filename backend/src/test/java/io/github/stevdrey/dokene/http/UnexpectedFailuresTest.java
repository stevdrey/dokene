// ABOUTME: Pins which failures a Phase 3 fallback handler may answer as INTERNAL_ERROR (issue 293) and which it must
// ABOUTME: rethrow so Spring MVC, the servlet container or Spring Security keep answering them.
package io.github.stevdrey.dokene.http;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.server.ResponseStatusException;

class UnexpectedFailuresTest {
    @Test
    void ordinaryRuntimeAndDataAccessFailuresAreUnexpected() {
        assertThat(UnexpectedFailures.isUnexpected(new IllegalStateException("x"))).isTrue();
        assertThat(UnexpectedFailures.isUnexpected(new PermissionDeniedDataAccessException("x", null))).isTrue();
        assertThat(UnexpectedFailures.isUnexpected(new NullPointerException())).isTrue();
    }

    @Test
    void springMvcServletAndSecurityFailuresAreNot() {
        assertThat(UnexpectedFailures.isUnexpected(new HttpRequestMethodNotSupportedException("DELETE"))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new ResponseStatusException(HttpStatus.NOT_FOUND))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new ServletException("x"))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new AccessDeniedException("x"))).isFalse();
        assertThat(UnexpectedFailures.isUnexpected(new BadCredentialsException("x"))).isFalse();
    }

    @Test
    void loggingSurvivesACyclicCauseChain() {
        IllegalStateException first = new IllegalStateException("a");
        IllegalArgumentException second = new IllegalArgumentException("b", first);
        first.initCause(second);
        UnexpectedFailures.log(first);
    }
}
