package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.CustomerFollowUpPolicy;
import io.github.stevdrey.dokene.followup.domain.TenantFollowUpPolicy;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.LocalDate;

public interface FollowUpPolicyRepository {
    TenantFollowUpPolicy tenantPolicy(TenantId tenantId);
    CustomerFollowUpPolicy customerPolicy(TenantId tenantId, CustomerId customerId);
    TenantFollowUpPolicy saveTenantPolicy(TenantFollowUpPolicy policy);
    CustomerFollowUpPolicy saveCustomerPolicy(CustomerFollowUpPolicy policy);
    CustomerFollowUpPolicy recordManualFollowUp(TenantId tenantId, CustomerId customerId, LocalDate date);
    CustomerFollowUpPolicy snooze(TenantId tenantId, CustomerId customerId, LocalDate until);
}
