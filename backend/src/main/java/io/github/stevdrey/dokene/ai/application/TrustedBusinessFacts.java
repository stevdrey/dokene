package io.github.stevdrey.dokene.ai.application;

/**
 * Explicitly supplied, authoritative tenant business facts for grounding follow-up drafts.
 */
public record TrustedBusinessFacts(String businessName, String preferredLocale) {
    public static final int MAX_BUSINESS_NAME_LENGTH = 160;
    public static final String DEFAULT_LOCALE = "es-419";

    public TrustedBusinessFacts {
        if (businessName == null || businessName.isBlank()) {
            throw new IllegalArgumentException("Business name cannot be blank");
        }
        if (businessName.codePointCount(0, businessName.length()) > MAX_BUSINESS_NAME_LENGTH) {
            throw new IllegalArgumentException("Business name exceeds maximum length of " + MAX_BUSINESS_NAME_LENGTH);
        }
        businessName = businessName.strip();
        preferredLocale = (preferredLocale == null || preferredLocale.isBlank())
                ? DEFAULT_LOCALE
                : preferredLocale.strip();
    }

    public TrustedBusinessFacts(String businessName) {
        this(businessName, DEFAULT_LOCALE);
    }
}
