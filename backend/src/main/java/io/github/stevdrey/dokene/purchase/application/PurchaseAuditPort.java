package io.github.stevdrey.dokene.purchase.application;

import io.github.stevdrey.dokene.purchase.domain.PurchaseId;

public interface PurchaseAuditPort {
    void recorded(PurchaseId id);
    void corrected(PurchaseId id);
    void voided(PurchaseId id);
}
