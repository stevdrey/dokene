// ABOUTME: A failure raised by a filter, before any controller advice, on a Phase 3 route answers the closed 500
// ABOUTME: INTERNAL_ERROR problem over a real server (issue 293); other routes keep Spring Boot's own error body.
package io.github.stevdrey.dokene.tenant.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "dokene.ai.provider=fake")
@Import(Phase3ErrorPageSystemTest.ExplodingFilterConfiguration.class)
class Phase3ErrorPageSystemTest {
    private static final String EXPLODE = "X-Test-Explode";
    private static final String CANARY = "CANARY-FILTER-FAILURE permission denied for table secret_table";

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) throws Exception {
        TenantSecurityIntegrationFixture.configure(registry);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void aFilterFailureOnAPhase3RouteIsTheClosedProblem() throws Exception {
        UUID messageId = UUID.randomUUID();
        HttpResponse<String> response = get("/api/messages/" + messageId);

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode problem = json.readTree(response.body());
        assertThat(problem.propertyNames()).containsExactlyInAnyOrder("type", "title", "status", "code");
        assertThat(problem.path("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(problem.path("title").asString()).isEqualTo("Internal Server Error");
        assertThat(response.body()).doesNotContain(messageId.toString(), "CANARY", "secret_table", "timestamp",
                "\"path\"");
        // Only the body changes: the response carries the same policy headers as Spring Boot's own error page.
        HttpResponse<String> bootPage = get("/api/customers");
        for (String header : new String[] {"X-Content-Type-Options", "X-Frame-Options", "Cache-Control"}) {
            assertThat(response.headers().firstValue(header)).as(header)
                    .isEqualTo(bootPage.headers().firstValue(header));
        }
    }

    @Test
    void theTemplateIntegrationAndSubmitRoutesAreCoveredToo() throws Exception {
        for (String path : new String[] {"/api/templates", "/api/integrations/whatsapp",
                "/api/customers/" + UUID.randomUUID() + "/messages"}) {
            HttpResponse<String> response = get(path);
            assertThat(response.statusCode()).as(path).isEqualTo(500);
            assertThat(json.readTree(response.body()).path("code").asString()).as(path).isEqualTo("INTERNAL_ERROR");
        }
    }

    @Test
    void otherRoutesKeepSpringBootsErrorBody() throws Exception {
        HttpResponse<String> response = get("/api/customers");

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).doesNotContain("INTERNAL_ERROR").contains("\"path\"");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(EXPLODE, "1").header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ExplodingFilterConfiguration {
        /** Fails every marked request before Spring Security or any controller, as a broken filter would. */
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> explodingFilter() {
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(
                    new OncePerRequestFilter() {
                        @Override
                        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
                            if (request.getHeader(EXPLODE) != null) {
                                throw new IllegalStateException(CANARY);
                            }
                            chain.doFilter(request, response);
                        }
                    });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
