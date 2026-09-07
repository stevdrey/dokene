package io.github.stevdrey.dokene.customer.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;

public interface CustomerAuditPort {
    void created(CustomerId customerId);
    void updated(CustomerId customerId);
    void archived(CustomerId customerId);
}
