package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;

public interface ContactPolicyAuditPort {
    void consentChanged(CustomerId customerId);
    void doNotContactChanged(CustomerId customerId);
}
