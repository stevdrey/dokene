package io.github.stevdrey.dokene.audit.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Internal jobs establish correlation explicitly; ordinary executor tasks do not inherit it. */
@Component
public final class AuditExecutionContext {
    private final ScopedValue<UUID> correlation = ScopedValue.newInstance();

    public Optional<UUID> current() {
        return correlation.isBound() ? Optional.of(correlation.get()) : Optional.empty();
    }

    public UUID requireCurrent() {
        return current().orElseThrow(() -> new IllegalStateException("Audit correlation scope is required"));
    }

    public void runWithCorrelation(UUID id, Runnable operation) {
        ScopedValue.where(correlation, Objects.requireNonNull(id, "Correlation ID is required")).run(operation);
    }

    public <T, X extends Throwable> T callWithCorrelation(UUID id, Operation<T, X> operation) throws X {
        return ScopedValue.where(correlation, Objects.requireNonNull(id, "Correlation ID is required"))
                .call(operation::execute);
    }

    @FunctionalInterface
    public interface Operation<T, X extends Throwable> {
        T execute() throws X;
    }
}
