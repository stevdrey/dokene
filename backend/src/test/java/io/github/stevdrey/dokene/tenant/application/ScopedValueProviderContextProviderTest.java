// ABOUTME: Unit tests for the ScopedValue backed provider context: bound only inside the scoped call, never inherited.
// ABOUTME: Covers the provider context half of AC-7 of spec 0001 (the webhook boundary binds it explicitly).
package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ScopedValueProviderContextProviderTest {
    private final ScopedValueProviderContextProvider provider = new ScopedValueProviderContextProvider();

    @Test
    void isUnboundByDefaultAndRequireCurrentFailsClosed() {
        assertThat(provider.current()).isEmpty();
        assertThatThrownBy(provider::requireCurrent).isInstanceOf(TenantContextUnavailableException.class);
    }

    @Test
    void bindsOnlyForTheDurationOfTheCallAndAllowsNestedRebinding() {
        ProviderContext outer = new ProviderContext(TenantId.random());
        ProviderContext inner = new ProviderContext(TenantId.random());

        ProviderContext seenInside = provider.callWithContext(outer, () -> {
            ProviderContext nested = provider.callWithContext(inner, provider::requireCurrent);
            assertThat(nested).isEqualTo(inner);
            return provider.requireCurrent();
        });

        assertThat(seenInside).isEqualTo(outer);
        assertThat(provider.current()).isEmpty();
    }

    @Test
    void isNotInheritedByAnotherThread() throws InterruptedException {
        AtomicReference<java.util.Optional<ProviderContext>> seen = new AtomicReference<>();

        provider.callWithContext(new ProviderContext(TenantId.random()), () -> {
            Thread worker = new Thread(() -> seen.set(provider.current()));
            worker.start();
            worker.join();
            return null;
        });

        assertThat(seen.get()).isEmpty();
    }

    @Test
    void rejectsNullContextOrOperation() {
        assertThatThrownBy(() -> provider.callWithContext(null, () -> null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> provider.callWithContext(new ProviderContext(TenantId.random()), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProviderContext(null)).isInstanceOf(NullPointerException.class);
    }
}
