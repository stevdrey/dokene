package io.github.stevdrey.dokene.template.application;

import java.util.Objects;

/** A refusal carrying only a closed code; the message never names ids, tenants, template names or provider text. */
public class TemplateRefusedException extends RuntimeException {
    private final TemplateErrorCode code;

    public TemplateRefusedException(TemplateErrorCode code) {
        super(Objects.requireNonNull(code, "Code is required").name());
        this.code = code;
    }

    public TemplateErrorCode code() {
        return code;
    }
}
