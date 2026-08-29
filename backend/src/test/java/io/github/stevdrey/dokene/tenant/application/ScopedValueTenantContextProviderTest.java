package io.github.stevdrey.dokene.tenant.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ScopedValueTenantContextProviderTest {

    private final ScopedValueTenantContextProvider provider = new ScopedValueTenantContextProvider();

    @Test
    void requiresAnExplicitContextAndClearsItWhenScopeCompletes() {
        TenantContext context = context();

        assertThatThrownBy(provider::requireCurrent).isInstanceOf(TenantContextUnavailableException.class);
        assertThat(provider.current()).isEmpty();

        provider.runWithContext(context, () -> {
            assertThat(provider.requireCurrent()).isEqualTo(context);
            assertThat(provider.current()).contains(context);
        });

        assertThat(provider.current()).isEmpty();
        assertThatThrownBy(provider::requireCurrent).isInstanceOf(TenantContextUnavailableException.class);
    }

    @Test
    void callWithContextReturnsValueAndClearsContext() throws Exception {
        TenantContext context = context();

        String result = provider.callWithContext(context, () -> {
            assertThat(provider.requireCurrent()).isEqualTo(context);
            return "success";
        });

        assertThat(result).isEqualTo("success");
        assertThat(provider.current()).isEmpty();
    }

    @Test
    void callWithContextPropagatesCheckedExceptionsAndClearsContext() {
        TenantContext context = context();

        assertThatThrownBy(() -> provider.callWithContext(context, () -> {
            assertThat(provider.requireCurrent()).isEqualTo(context);
            throw new IOException("checked error");
        }))
                .isInstanceOf(IOException.class)
                .hasMessage("checked error");

        assertThat(provider.current()).isEmpty();
    }

    @Test
    void runWithContextPropagatesUncheckedExceptionsAndClearsContext() {
        TenantContext context = context();

        assertThatThrownBy(() -> provider.runWithContext(context, () -> {
            assertThat(provider.requireCurrent()).isEqualTo(context);
            throw new IllegalStateException("downstream error");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("downstream error");

        assertThat(provider.current()).isEmpty();
    }

    @Test
    void doesNotLeakContextWhenAWorkerThreadIsReused() throws Exception {
        TenantContext context = context();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertThat(executor.submit(provider::current).get()).isEmpty();
            assertThat(executor.submit(() -> {
                return provider.callWithContext(context, () -> provider.requireCurrent());
            }).get()).isEqualTo(context);
            assertThat(executor.submit(provider::current).get()).isEmpty();
        }
    }

    @Test
    void doesNotInheritContextIntoAsynchronousWorkImplicitly() throws Exception {
        TenantContext context = context();

        provider.runWithContext(context, () -> {
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                assertThatThrownBy(() -> executor.submit(provider::requireCurrent).get())
                        .hasCauseInstanceOf(TenantContextUnavailableException.class);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }

    @Test
    void scopesTenantIdExplicitlyAndClearsItWhenScopeCompletes() throws Exception {
        TenantId tenantId = TenantId.random();

        assertThat(provider.currentTenantId()).isEmpty();

        provider.runWithTenantId(tenantId, () -> {
            assertThat(provider.currentTenantId()).contains(tenantId);
            assertThat(provider.current()).isEmpty();
        });

        assertThat(provider.currentTenantId()).isEmpty();

        String result = provider.callWithTenantId(tenantId, () -> {
            assertThat(provider.currentTenantId()).contains(tenantId);
            return "tenant-scoped";
        });

        assertThat(result).isEqualTo("tenant-scoped");
        assertThat(provider.currentTenantId()).isEmpty();
    }

    @Test
    void derivesTenantIdFromFullTenantContext() {
        TenantContext context = context();

        provider.runWithContext(context, () -> {
            assertThat(provider.currentTenantId()).contains(context.tenantId());
        });

        assertThat(provider.currentTenantId()).isEmpty();
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
