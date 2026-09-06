package io.github.stevdrey.dokene.identity.application;

import java.net.URI;
import java.util.Objects;

/** Provider-owned identifier presented by a validated OIDC authentication. */
public record OidcIdentityMapping(URI issuer, String subject) {

    private static final int MAX_ISSUER_LENGTH = 2048;
    private static final int MAX_SUBJECT_LENGTH = 255;

    public OidcIdentityMapping {
        Objects.requireNonNull(issuer, "OIDC issuer is required");
        Objects.requireNonNull(subject, "OIDC subject is required");
        if (!issuer.isAbsolute() || issuer.getFragment() != null || issuer.getQuery() != null
                || issuer.toString().length() > MAX_ISSUER_LENGTH) {
            throw new IllegalArgumentException("OIDC issuer must be an absolute URI without query or fragment");
        }
        if (subject.isBlank() || subject.length() > MAX_SUBJECT_LENGTH) {
            throw new IllegalArgumentException("OIDC subject must contain between 1 and 255 characters");
        }
    }
}
