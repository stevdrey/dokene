package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.ZoneId;
import java.util.Objects;

public record TenantFollowUpPolicy(TenantId tenantId, int cadenceDays, ZoneId zoneId, long version) {
    public static final int MAX_CADENCE_DAYS = 3_650;

    public TenantFollowUpPolicy {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(zoneId, "Tenant time zone is required");
        if (!ZoneId.getAvailableZoneIds().contains(zoneId.getId())) {
            throw new IllegalArgumentException("Tenant time zone must be an IANA zone ID");
        }
        if (version < 0) throw new IllegalArgumentException("Version cannot be negative");
        if (cadenceDays < 1 || cadenceDays > MAX_CADENCE_DAYS) {
            throw new IllegalArgumentException("Cadence must be between 1 and 3650 days");
        }
    }

    public TenantFollowUpPolicy(TenantId tenantId, int cadenceDays, ZoneId zoneId) {
        this(tenantId, cadenceDays, zoneId, 0);
    }
}
