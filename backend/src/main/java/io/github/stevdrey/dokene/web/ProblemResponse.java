// ABOUTME: The closed RFC 9457 problem body Phase 3 routes return: type, title, status and a module error code.
// ABOUTME: Exactly four members by construction; no detail, no instance, no free text, no identifiers.
package io.github.stevdrey.dokene.web;

import java.util.Objects;
import org.springframework.http.HttpStatus;

public record ProblemResponse(String type, String title, int status, String code) {
    public static final String ABOUT_BLANK = "about:blank";

    public ProblemResponse {
        Objects.requireNonNull(type, "Type is required");
        Objects.requireNonNull(title, "Title is required");
        Objects.requireNonNull(code, "Code is required");
        if (code.isBlank()) {
            throw new IllegalArgumentException("Code is required");
        }
    }

    /** The standard shape: {@code about:blank}, the HTTP reason phrase as the title, the status and the code. */
    public static ProblemResponse of(HttpStatus status, String code) {
        Objects.requireNonNull(status, "Status is required");
        return new ProblemResponse(ABOUT_BLANK, status.getReasonPhrase(), status.value(), code);
    }
}
