// ABOUTME: PostgreSQL tenant rotation for the follow-up scheduler (issue 463, ADR 0040): a signed maintenance capability
// ABOUTME: lets V51's finder list active tenant ids past tenant RLS, one bounded keyset page at a time.
package io.github.stevdrey.dokene.followup.persistence.jdbc;

import io.github.stevdrey.dokene.followup.application.FollowUpSchedulingTenants;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.MaintenanceTask;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFollowUpSchedulingTenants implements FollowUpSchedulingTenants {
    private final JdbcTemplate jdbc;
    private final DatabaseContextSigner signer;

    public JdbcFollowUpSchedulingTenants(JdbcTemplate jdbc, DatabaseContextSigner signer) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.signer = Objects.requireNonNull(signer);
    }

    @Override
    public List<TenantId> activeTenantsAfter(Optional<TenantId> after, int limit) {
        Objects.requireNonNull(after, "Cursor option is required");
        if (limit < 1) {
            throw new IllegalArgumentException("Tenant page limit must be positive");
        }
        // The function returns nothing without a capability for this task (V51, issue 313).
        SignedDatabaseContext capability = signer.issueMaintenanceContext(MaintenanceTask.FOLLOW_UP_SCHEDULING);
        return jdbc.query("""
                SELECT tenant_id FROM dokene.find_follow_up_scheduling_tenants(?, ?, ?::uuid, ?)
                """, (row, index) -> new TenantId(row.getObject("tenant_id", UUID.class)), capability.payload(),
                capability.signature(), after.map(TenantId::value).orElse(null), limit);
    }
}
