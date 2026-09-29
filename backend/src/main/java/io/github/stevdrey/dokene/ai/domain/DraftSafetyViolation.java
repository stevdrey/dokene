package io.github.stevdrey.dokene.ai.domain;

import java.util.Objects;

/**
 * Represents a deterministic draft safety constraint violation.
 * Encapsulates a closed, privacy-safe diagnostic code (safe for durable audit logging)
 * and an internal diagnostic description.
 */
public record DraftSafetyViolation(String code, String description) {

    public static final String UNAUTHORIZED_URL = "UNAUTHORIZED_URL";
    public static final String UNAUTHORIZED_PROVIDER_TEMPLATE = "UNAUTHORIZED_PROVIDER_TEMPLATE";
    public static final String HALLUCINATED_OFFER_SYMBOL = "HALLUCINATED_OFFER_SYMBOL";
    public static final String HALLUCINATED_OFFER_TERM = "HALLUCINATED_OFFER_TERM";
    public static final String HALLUCINATED_PERCENTAGE = "HALLUCINATED_PERCENTAGE";
    public static final String HALLUCINATED_PRICE = "HALLUCINATED_PRICE";
    public static final String UNGROUNDED_EVIDENCE = "UNGROUNDED_EVIDENCE";

    public DraftSafetyViolation {
        Objects.requireNonNull(code, "Violation code is required");
        Objects.requireNonNull(description, "Violation description is required");
    }

    public static DraftSafetyViolation of(String code, String description) {
        return new DraftSafetyViolation(code, description);
    }
}
