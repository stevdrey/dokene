package io.github.stevdrey.dokene.template.api;

import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.http.UnexpectedFailures;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.InvalidRequestException;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateProblem;
import io.github.stevdrey.dokene.template.application.TemplateErrorCode;
import io.github.stevdrey.dokene.template.application.TemplateRefusedException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Closed-code problem+json only: no detail, ids, template names or provider text. */
@RestControllerAdvice(assignableTypes = TemplateController.class)
public class TemplateExceptionHandler {
    private final DatabaseUnavailability databaseUnavailability;

    public TemplateExceptionHandler(DatabaseUnavailability databaseUnavailability) {
        this.databaseUnavailability = databaseUnavailability;
    }

    @ExceptionHandler(TemplateRefusedException.class)
    ResponseEntity<TemplateProblem> refused(TemplateRefusedException refusal) {
        return problem(refusal.code());
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<TemplateProblem> forbidden() {
        return problem(TemplateErrorCode.FORBIDDEN);
    }

    @ExceptionHandler({InvalidRequestException.class, HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class, HttpMediaTypeNotAcceptableException.class})
    ResponseEntity<TemplateProblem> invalid() {
        return problem(TemplateErrorCode.INVALID_INPUT);
    }

    /**
     * The last resort (issue 293): the closed {@code 500 INTERNAL_ERROR}, or a rethrow, as
     * {@link UnexpectedFailures#claim} decides.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<TemplateProblem> unexpected(Exception failure, HttpServletResponse response) throws Exception {
        if (!UnexpectedFailures.claim(failure, response, databaseUnavailability::isCause)) {
            throw failure;
        }
        return problem(TemplateErrorCode.INTERNAL_ERROR);
    }

    private static ResponseEntity<TemplateProblem> problem(TemplateErrorCode code) {
        return ResponseEntity.status(HttpStatus.valueOf(code.status()))
                .contentType(MediaType.parseMediaType(TemplateApiContract.PROBLEM_MEDIA_TYPE))
                .body(TemplateProblem.of(code));
    }
}
