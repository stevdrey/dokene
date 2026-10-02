package io.github.stevdrey.dokene.audit.domain;

/**
 * Closed vocabulary explaining an AI outcome. NONE for GENERATED and MODEL_REFUSED (the refusal reason code is
 * intentionally not stored), a failure category for FAILED, an Action Gate rejection reason for GATE_REJECTED.
 * Never free text. Mirrors the {@code ck_audit_shape} constraint in V13 and the boundary constraint in V14.
 */
public enum AiAuditDetail {
    NONE(Kind.NONE),
    // failure categories and context failures (FAILED)
    TIMEOUT(Kind.FAILURE), THROTTLED(Kind.FAILURE), UNAVAILABLE(Kind.FAILURE),
    INVALID_STRUCTURED_RESPONSE(Kind.FAILURE), REJECTED_REQUEST(Kind.FAILURE), CANCELLED(Kind.FAILURE),
    NOT_AVAILABLE(Kind.FAILURE), REFUSED(Kind.FAILURE),
    CONTEXT_TOO_LARGE(Kind.FAILURE), CONTEXT_UNSUPPORTED(Kind.FAILURE),
    // Action Gate rejection reasons (GATE_REJECTED). NO_TENANT_CONTEXT, UNAUTHORIZED and CUSTOMER_NOT_FOUND are
    // deliberately absent: they are raised before the customer is verified to belong to the tenant, so the
    // caller-supplied id must never be audited (see V14 and AiOutcomeReporter).
    CUSTOMER_ARCHIVED(Kind.GATE), DO_NOT_CONTACT(Kind.GATE), NO_CONTACT_CONSENT(Kind.GATE),
    FOLLOW_UP_INELIGIBLE(Kind.GATE), STALE_STATE(Kind.GATE), DISALLOWED_ACTION(Kind.GATE),
    DISALLOWED_TEMPLATE_INTENT(Kind.GATE), INVALID_RECOMMENDATION(Kind.GATE);

    private enum Kind { NONE, FAILURE, GATE }

    private final Kind kind;

    AiAuditDetail(Kind kind) {
        this.kind = kind;
    }

    boolean isNone() {
        return kind == Kind.NONE;
    }

    boolean isFailure() {
        return kind == Kind.FAILURE;
    }

    boolean isGateReason() {
        return kind == Kind.GATE;
    }
}
