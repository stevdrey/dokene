package io.github.stevdrey.dokene.purchase.application;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.purchase.domain.Purchase;
import io.github.stevdrey.dokene.purchase.domain.PurchaseEvent;
import io.github.stevdrey.dokene.purchase.domain.PurchaseId;
import io.github.stevdrey.dokene.purchase.domain.PurchaseStatus;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PurchaseRepository {
    CreateResult insert(Purchase purchase, String idempotencyKey, String fingerprint, TenantContext actor);
    Optional<Purchase> findById(TenantId tenantId, CustomerId customerId, PurchaseId id);
    List<Purchase> list(TenantId tenantId, CustomerId customerId, PurchaseStatus status,
                        PurchaseCursor before, int limit);
    Optional<Purchase> lastValid(TenantId tenantId, CustomerId customerId);
    void update(Purchase purchase, long expectedVersion, PurchaseEvent.Type eventType, TenantContext actor);
    List<PurchaseEvent> history(Purchase purchase, PurchaseEventCursor before, int limit);

    record CreateResult(Purchase purchase, boolean created, boolean matchingRequest) { }
}
