package io.github.stevdrey.dokene.audit.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class AuditRequestFilterTest {
    private final AuditExecutionContext context = new AuditExecutionContext();
    private final AuditRequestFilter filter = new AuditRequestFilter(context);

    @Test
    void ignoresClientCorrelationAndClearsRequestScope() throws Exception {
        UUID supplied = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", supplied.toString());
        AtomicReference<UUID> first = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> first.set(context.requireCurrent()));
        assertThat(first.get()).isNotNull().isNotEqualTo(supplied);
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> assertThat(context.requireCurrent()).isNotEqualTo(first.get()));
        assertThat(context.current()).isEmpty();
    }

    @Test
    void convertsSecurityFilterFailureToGeneric503() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {
            throw new ServletException("untrusted exception details", new AuditPersistenceException());
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).isEmpty();
        assertThat(context.current()).isEmpty();
    }

    @Test
    void mvcFailureHasTheSameGeneric503Response() throws Exception {
        MockMvcBuilders.standaloneSetup(new FailingController()).setControllerAdvice(new AuditExceptionHandler())
                .addFilters(filter).build().perform(get("/failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString()).isEmpty());
    }

    @Test
    void doesNotHideUnrelatedFailures() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("unrelated");
        })).isInstanceOf(IllegalStateException.class).hasMessage("unrelated");
        assertThat(context.current()).isEmpty();
    }

    @RestController
    static class FailingController {
        @GetMapping("/failure")
        public void fail() { throw new AuditPersistenceException(); }
    }
}
