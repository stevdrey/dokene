package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.customer.application.CustomerAuditPort;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import org.springframework.stereotype.Component;

@Component
public class DurableCustomerAuditAdapter implements CustomerAuditPort {
    private final AuditRecorder recorder;

    public DurableCustomerAuditAdapter(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void created(CustomerId customerId) {
        recorder.customerMutated(customerId.value(), AuditEventType.CUSTOMER_CREATED);
    }

    @Override
    public void updated(CustomerId customerId) {
        recorder.customerMutated(customerId.value(), AuditEventType.CUSTOMER_UPDATED);
    }

    @Override
    public void archived(CustomerId customerId) {
        recorder.customerMutated(customerId.value(), AuditEventType.CUSTOMER_ARCHIVED);
    }
}
