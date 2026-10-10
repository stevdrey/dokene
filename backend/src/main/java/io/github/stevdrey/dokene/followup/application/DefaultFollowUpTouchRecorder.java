// ABOUTME: Default FollowUpTouchRecorder: computes the tenant local date and updates the customer policy row.
// ABOUTME: Mandatory propagation, so it only ever runs inside a message transition's transaction.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.application.TenantContextUnavailableException;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultFollowUpTouchRecorder implements FollowUpTouchRecorder {
    private final FollowUpPolicyRepository policies;
    private final TenantContextProvider contexts;

    public DefaultFollowUpTouchRecorder(FollowUpPolicyRepository policies, TenantContextProvider contexts) {
        this.policies = Objects.requireNonNull(policies, "Policy repository is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordOutboundMessage(CustomerId customerId, Instant sentAt) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(sentAt, "Sent time is required");
        // The member or provider boundary binds the tenant id; the touch never takes it from input.
        TenantId tenantId = contexts.currentTenantId().orElseThrow(TenantContextUnavailableException::new);
        LocalDate tenantDate = sentAt.atZone(policies.tenantPolicy(tenantId).zoneId()).toLocalDate();
        policies.recordOutboundMessage(tenantId, customerId, tenantDate);
    }
}
