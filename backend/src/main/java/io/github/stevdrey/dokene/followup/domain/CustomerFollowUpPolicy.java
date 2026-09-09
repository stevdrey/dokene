package io.github.stevdrey.dokene.followup.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.LocalDate;
import java.util.Objects;

public record CustomerFollowUpPolicy(TenantId tenantId, CustomerId customerId, Integer cadenceDays,
        LocalDate explicitNextDate, LocalDate snoozedUntil, LocalDate lastManualFollowUpDate) {
    public CustomerFollowUpPolicy {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        Objects.requireNonNull(customerId, "Customer ID is required");
        if (cadenceDays != null && (cadenceDays < 1 || cadenceDays > TenantFollowUpPolicy.MAX_CADENCE_DAYS)) {
            throw new IllegalArgumentException("Cadence must be between 1 and 3650 days");
        }
    }

    public static CustomerFollowUpPolicy empty(TenantId tenantId, CustomerId customerId) {
        return new CustomerFollowUpPolicy(tenantId, customerId, null, null, null, null);
    }
}
