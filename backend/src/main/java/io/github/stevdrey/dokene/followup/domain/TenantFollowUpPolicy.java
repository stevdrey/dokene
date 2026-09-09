package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.ZoneId;
import java.util.Objects;

public record TenantFollowUpPolicy(TenantId tenantId, int cadenceDays, ZoneId zoneId) {
    public static final int MAX_CADENCE_DAYS = 3_650;

    public TenantFollowUpPolicy {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(zoneId, "Tenant time zone is required");
        if (cadenceDays < 1 || cadenceDays > MAX_CADENCE_DAYS) {
            throw new IllegalArgumentException("Cadence must be between 1 and 3650 days");
        }
    }
}
