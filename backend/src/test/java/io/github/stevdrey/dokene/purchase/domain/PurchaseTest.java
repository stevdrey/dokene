package io.github.stevdrey.dokene.purchase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PurchaseTest {
    @Test
    void correctsAndVoidsWithoutChangingIdentity() {
        Instant now = Instant.parse("2026-09-07T12:00:00Z");
        Purchase purchase = Purchase.create(new PurchaseId(UUID.randomUUID()), TenantId.random(),
                new CustomerId(UUID.randomUUID()), now.minusSeconds(60), "  Coffee beans  ", now);
        purchase.correct(now.minusSeconds(120), "Ground coffee", now.plusSeconds(1));
        assertThat(purchase.description()).isEqualTo("Ground coffee");
        assertThat(purchase.voidPurchase(now.plusSeconds(2))).isTrue();
        assertThat(purchase.status()).isEqualTo(PurchaseStatus.VOID);
        assertThat(purchase.voidPurchase(now.plusSeconds(3))).isFalse();
        assertThatThrownBy(() -> purchase.correct(now, "No", now.plusSeconds(4)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankAndOversizedDescriptions() {
        assertThatThrownBy(() -> Purchase.create(new PurchaseId(UUID.randomUUID()), TenantId.random(),
                new CustomerId(UUID.randomUUID()), Instant.now(), "  ", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Purchase.create(new PurchaseId(UUID.randomUUID()), TenantId.random(),
                new CustomerId(UUID.randomUUID()), Instant.now(), "x".repeat(501), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
