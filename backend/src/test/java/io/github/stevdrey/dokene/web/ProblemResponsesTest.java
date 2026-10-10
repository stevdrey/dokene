// ABOUTME: Pins the problem body shape: four members, about:blank, the reason phrase, problem+json (spec 0002 AC-4).
package io.github.stevdrey.dokene.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

class ProblemResponsesTest {
    @Test
    void writesExactlyFourMembersInOrder() {
        String json = new ObjectMapper().writeValueAsString(ProblemResponse.of(HttpStatus.CONFLICT, "STALE_VERSION"));
        assertThat(json).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,\"code\":\"STALE_VERSION\"}");
    }

    @Test
    void respondsWithTheProblemContentTypeAndExtraHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");
        var response = ProblemResponses.respond(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", headers);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getBody()).isEqualTo(new ProblemResponse("about:blank", "Too Many Requests", 429, "RATE_LIMITED"));
    }

    @Test
    void refusesABlankCode() {
        assertThatThrownBy(() -> ProblemResponse.of(HttpStatus.BAD_REQUEST, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
