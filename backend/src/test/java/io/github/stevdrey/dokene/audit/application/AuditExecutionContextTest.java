package io.github.stevdrey.dokene.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class AuditExecutionContextTest {
    private final AuditExecutionContext context = new AuditExecutionContext();

    @Test
    void restoresNestedScopesEvenAfterFailure() {
        UUID outer = UUID.randomUUID();
        UUID inner = UUID.randomUUID();
        context.runWithCorrelation(outer, () -> {
            assertThat(context.requireCurrent()).isEqualTo(outer);
            assertThatThrownBy(() -> context.runWithCorrelation(inner, () -> {
                assertThat(context.requireCurrent()).isEqualTo(inner);
                throw new IllegalArgumentException("test");
            })).isInstanceOf(IllegalArgumentException.class);
            assertThat(context.requireCurrent()).isEqualTo(outer);
        });
        assertThat(context.current()).isEmpty();
        assertThatThrownBy(context::requireCurrent).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ordinaryExecutorNeedsAnExplicitScope() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            context.callWithCorrelation(UUID.randomUUID(), () -> {
                assertThat(executor.submit(context::current).get()).isEmpty();
                return null;
            });
            UUID task = UUID.randomUUID();
            assertThat(executor.submit(() -> context.callWithCorrelation(task, context::requireCurrent)).get()).isEqualTo(task);
            assertThat(executor.submit(context::current).get()).isEmpty();
        }
    }
}
