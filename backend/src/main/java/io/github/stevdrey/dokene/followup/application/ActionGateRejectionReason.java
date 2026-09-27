package io.github.stevdrey.dokene.followup.application;

/**
 * Closed, typed rejection reasons for AI recommendations and drafts evaluated by the AI Action Gate.
 * Provides safe diagnostics suitable for UX display and security telemetry without sensitive content.
 */
public enum ActionGateRejectionReason {
    /** The active authenticated tenant context is missing or unavailable. */
    NO_TENANT_CONTEXT,

    /** The caller lacks authorization to evaluate follow-up recommendations for the customer. */
    UNAUTHORIZED,

    /** The target customer was not found in the authenticated tenant boundary. */
    CUSTOMER_NOT_FOUND,

    /** The customer is archived or inactive. */
    CUSTOMER_ARCHIVED,

    /** The customer has an active do-not-contact restriction. */
    DO_NOT_CONTACT,

    /** The customer does not have granted contact consent for the messaging channel. */
    NO_CONTACT_CONSENT,

    /** The customer is not currently due or overdue for follow-up. */
    FOLLOW_UP_INELIGIBLE,

    /**
     * Authoritative application state changed between recommendation context assembly
     * and result acceptance (e.g. new purchase, modified cadence, date rollover).
     */
    STALE_STATE,

    /** The recommended semantic action is not allowed for the customer context or tenant policy. */
    DISALLOWED_ACTION,

    /** The recommended semantic template intent is not allowed or is incompatible with the action. */
    DISALLOWED_TEMPLATE_INTENT,

    /** The recommendation violates application constraints or contains malformed draft parameters. */
    INVALID_RECOMMENDATION
}
