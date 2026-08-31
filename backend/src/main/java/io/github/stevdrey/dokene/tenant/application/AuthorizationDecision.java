package io.github.stevdrey.dokene.tenant.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of an authorization evaluation.
 */
public record AuthorizationDecision(boolean isAllowed, String reason) {

    public static AuthorizationDecision allow() {
        return new AuthorizationDecision(true, null);
    }

    public static AuthorizationDecision deny(String reason) {
        return new AuthorizationDecision(false, Objects.requireNonNull(reason, "Denial reason is required"));
    }

    public Optional<String> rejectionReason() {
        return Optional.ofNullable(reason);
    }
}
