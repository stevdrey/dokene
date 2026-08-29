package io.github.stevdrey.dokene.tenant.domain;

import java.util.UUID;

public record TenantMembershipId(UUID value) {

    public TenantMembershipId {
        if (value == null) {
            throw new IllegalArgumentException("Tenant membership ID is required");
        }
    }

    public static TenantMembershipId random() {
        return new TenantMembershipId(UUID.randomUUID());
    }
}
