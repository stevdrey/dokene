package io.github.stevdrey.dokene.ai.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Supplies the server-generated request correlation identifier for safe diagnostic propagation.
 * Keeps the AI module independent of how correlation is established.
 */
@FunctionalInterface
public interface AiCorrelationSource {
    Optional<UUID> current();

    static AiCorrelationSource none() {
        return Optional::empty;
    }
}
