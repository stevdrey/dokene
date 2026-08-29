package io.github.stevdrey.dokene.tenant.domain;

import java.util.UUID;

public record TenantId(UUID value) {

    public TenantId {
        if (value == null) {
            throw new IllegalArgumentException("Tenant ID is required");
        }
    }

    public static TenantId random() {
        return new TenantId(UUID.randomUUID());
    }
}
