package io.github.stevdrey.dokene.template.api;

import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.InvalidRequestException;
import io.github.stevdrey.dokene.template.api.TemplateApiContract.TemplateProblem;
import io.github.stevdrey.dokene.template.application.TemplateErrorCode;
import io.github.stevdrey.dokene.template.application.TemplateRefusedException;
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

    private static ResponseEntity<TemplateProblem> problem(TemplateErrorCode code) {
        return ResponseEntity.status(HttpStatus.valueOf(code.status()))
                .contentType(MediaType.parseMediaType(TemplateApiContract.PROBLEM_MEDIA_TYPE))
                .body(TemplateProblem.of(code));
    }
}
