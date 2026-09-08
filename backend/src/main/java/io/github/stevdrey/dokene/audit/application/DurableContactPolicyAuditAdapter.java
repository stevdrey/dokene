package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.customer.application.ContactPolicyAuditPort;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import org.springframework.stereotype.Component;

@Component
public class DurableContactPolicyAuditAdapter implements ContactPolicyAuditPort {
    private final AuditRecorder recorder;

    public DurableContactPolicyAuditAdapter(AuditRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public void consentChanged(CustomerId customerId) {
        recorder.customerMutated(customerId.value(), AuditEventType.CUSTOMER_CONSENT_CHANGED);
    }

    @Override
    public void doNotContactChanged(CustomerId customerId) {
        recorder.customerMutated(customerId.value(), AuditEventType.CUSTOMER_DO_NOT_CONTACT_CHANGED);
    }
}
