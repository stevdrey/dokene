// ABOUTME: Startup failure report for bind errors under dokene.integration.secrets that names properties only.
// ABOUTME: Replaces Spring Boot's analyzers for this prefix, which print the bound value of every unbound property.
package io.github.stevdrey.dokene.integration.secrets;

import java.util.stream.Collectors;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

@Order(Ordered.HIGHEST_PRECEDENCE)
public final class SecretBindFailureAnalyzer extends AbstractFailureAnalyzer<BindException> {
    static final String PREFIX = "dokene.integration.secrets";

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, BindException cause) {
        if (cause.getName() == null || !cause.getName().toString().startsWith(PREFIX)) {
            return null;
        }
        UnboundConfigurationPropertiesException unbound = findCause(cause, UnboundConfigurationPropertiesException.class);
        String description = unbound == null
                ? "The configuration under '" + cause.getName() + "' could not be bound."
                : "Secret entries were left unbound: " + unbound.getUnboundProperties().stream()
                        .map(property -> property.getName().toString()).sorted().collect(Collectors.joining(", "))
                        + ".";
        return new FailureAnalysis(description,
                "Keep dokene.integration.secrets.local.entries[<n>] indices contiguous from 0 and give each entry only "
                        + "the fields scope, tenant-id, ref, purpose and value. Values are never printed here.",
                cause);
    }
}
