package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ThreadLocalTenantContextProviderTest {

    @Test
    void requiresAnExplicitContextAndClearsItWhenTheScopeCloses() {
        ThreadLocalTenantContextProvider provider = new ThreadLocalTenantContextProvider();
        TenantContext context = context();

        assertThatThrownBy(provider::requireCurrent).isInstanceOf(TenantContextUnavailableException.class);

        try (TenantContextScope ignored = provider.establish(context)) {
            assertThat(provider.requireCurrent()).isEqualTo(context);
        }

        assertThat(provider.current()).isEmpty();
    }

    @Test
    void doesNotLeakContextWhenAWorkerThreadIsReused() throws Exception {
        ThreadLocalTenantContextProvider provider = new ThreadLocalTenantContextProvider();
        TenantContext context = context();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertThat(executor.submit(provider::current).get()).isEmpty();
            assertThat(executor.submit(() -> {
                try (TenantContextScope ignored = provider.establish(context)) {
                    return provider.requireCurrent();
                }
            }).get()).isEqualTo(context);
            assertThat(executor.submit(provider::current).get()).isEmpty();
        }
    }

    @Test
    void doesNotInheritContextIntoAsynchronousWork() throws Exception {
        ThreadLocalTenantContextProvider provider = new ThreadLocalTenantContextProvider();

        try (TenantContextScope ignored = provider.establish(context());
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertThatThrownBy(() -> executor.submit(provider::requireCurrent).get())
                    .hasCauseInstanceOf(TenantContextUnavailableException.class);
        }
    }

    private TenantContext context() {
        return new TenantContext(
                TenantId.random(),
                new IdentityId(UUID.randomUUID()),
                TenantMembershipId.random(),
                TenantRole.ADMIN
        );
    }
}
