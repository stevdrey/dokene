// ABOUTME: Port to the active tenants the follow-up scheduler rotates through (issue 463, ADR 0040): one bounded keyset
// ABOUTME: page of tenant ids after a cursor. The only cross-tenant read the scheduler makes; ids only.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.List;
import java.util.Optional;

public interface FollowUpSchedulingTenants {

    /**
     * Active tenants after {@code after} (or from the first when empty), in ascending id order, at most {@code limit}.
     * A page shorter than {@code limit} means the rotation reached the end and starts again from the first tenant.
     */
    List<TenantId> activeTenantsAfter(Optional<TenantId> after, int limit);
}
