// ABOUTME: The closed set of refusal codes feature 2 maps to problem+json (ADR 0023 §4.4).
// ABOUTME: Codes not yet raised are declared now; the set is frozen by the v1 contract fixtures (ADR 0024 §1).
package io.github.stevdrey.dokene.messaging.domain;

public enum MessagingErrorCode {
    INVALID_INPUT, FORBIDDEN, NOT_FOUND, STALE_VERSION, IDEMPOTENCY_KEY_REUSED, INVALID_TRANSITION,
    CUSTOMER_ARCHIVED, DO_NOT_CONTACT, NO_CONTACT_CONSENT, CONTACT_NOT_OWNED, FOLLOW_UP_INELIGIBLE,
    MESSAGE_ALREADY_OPEN, CONTACT_FREQUENCY_EXCEEDED, TEMPLATE_NOT_MAPPED, BODY_REJECTED,
    OUTBOUND_DISABLED_GLOBALLY, OUTBOUND_DISABLED_FOR_TENANT, INTEGRATION_DISABLED, SEND_ATTEMPTS_EXHAUSTED,
    RATE_LIMITED, PROVIDER_UNAVAILABLE, APPROVAL_INVALIDATED,
    /** A supported locale this deployment does not accept yet ({@code en-US} before English writes are enabled). */
    LOCALE_NOT_ACCEPTED,
    /**
     * An unexpected server failure on a Phase 3 route (issue 293). It always answers 500 and never carries detail;
     * database outages and audit failures keep their own empty 503 (ADR 0029, ADR 0006).
     */
    INTERNAL_ERROR
}
