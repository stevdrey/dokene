// ABOUTME: The only resolver: reads the deployment environment of the server process for the env scheme.
// ABOUTME: Only names under DOKENE_WA_ can reach it (the SecretRef grammar), so no other variable is readable.
package io.github.stevdrey.dokene.integration.config;

import java.util.Objects;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class EnvironmentIntegrationSecretResolver implements IntegrationSecretResolver {
    private final Environment environment;

    public EnvironmentIntegrationSecretResolver(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "Environment is required");
    }

    @Override
    public Optional<SecretValue> resolve(SecretRef ref) {
        Objects.requireNonNull(ref, "Secret reference is required");
        if (!SecretRef.ENV_SCHEME.equals(ref.scheme())) {
            return Optional.empty();
        }
        String value = environment.getProperty(ref.name());
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(new SecretValue(value));
    }
}
