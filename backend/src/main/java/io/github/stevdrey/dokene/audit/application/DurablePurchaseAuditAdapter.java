package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.purchase.application.PurchaseAuditPort;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import org.springframework.stereotype.Component;

@Component
public class DurablePurchaseAuditAdapter implements PurchaseAuditPort {
    private final AuditRecorder recorder;
    public DurablePurchaseAuditAdapter(AuditRecorder recorder) { this.recorder = recorder; }
    @Override public void recorded(PurchaseId id) { recorder.purchaseMutated(id.value(), AuditEventType.PURCHASE_RECORDED); }
    @Override public void corrected(PurchaseId id) { recorder.purchaseMutated(id.value(), AuditEventType.PURCHASE_CORRECTED); }
    @Override public void voided(PurchaseId id) { recorder.purchaseMutated(id.value(), AuditEventType.PURCHASE_VOIDED); }
}
