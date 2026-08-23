package io.github.stevdrey.dokene.tenant.domain;

import java.util.UUID;

/**
 * Stable internal identity reference. Its resolution belongs to the future identity module.
 */
public record IdentityId(UUID value) {

    public IdentityId {
        if (value == null) {
            throw new IllegalArgumentException("Identity ID is required");
        }
    }
}
