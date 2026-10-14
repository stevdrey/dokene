// ABOUTME: Closed refusal codes of the template API; each name is also a MessagingErrorCode (pinned by a parity test).
// ABOUTME: Owned here because the template module may not depend on messaging (ADR 0024 §3).
package io.github.stevdrey.dokene.template.application;

public enum TemplateErrorCode {
    INVALID_INPUT(400), FORBIDDEN(403), NOT_FOUND(404), STALE_VERSION(409), INTEGRATION_DISABLED(409),
    PROVIDER_UNAVAILABLE(503);

    private final int status;

    TemplateErrorCode(int status) {
        this.status = status;
    }

    public int status() {
        return status;
    }
}
