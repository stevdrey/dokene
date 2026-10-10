// ABOUTME: Builds application/problem+json responses from a status and a closed code (ADR 0023 §4.4).
// ABOUTME: Shared by every Phase 3 controller advice so the body shape and content type are written once.
package io.github.stevdrey.dokene.web;

import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

public final class ProblemResponses {
    private ProblemResponses() {
    }

    public static ResponseEntity<ProblemResponse> respond(HttpStatus status, String code) {
        return respond(status, code, new HttpHeaders());
    }

    public static ResponseEntity<ProblemResponse> respond(HttpStatus status, String code, HttpHeaders headers) {
        Objects.requireNonNull(headers, "Headers are required");
        return ResponseEntity.status(status).headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ProblemResponse.of(status, code));
    }
}
