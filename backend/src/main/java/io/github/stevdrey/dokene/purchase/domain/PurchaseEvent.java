package io.github.stevdrey.dokene.purchase.domain;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import java.time.Instant;
import java.util.UUID;

public record PurchaseEvent(UUID id, Type type, Instant purchasedAt, String description,
                            Instant occurredAt, IdentityId actorId,
                            TenantMembershipId membershipId, long purchaseVersion) {
    public enum Type { RECORDED, CORRECTED, VOIDED }
}
