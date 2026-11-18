// ABOUTME: Wires workspace provisioning (spec 0021): binds and validates dokene.provisioning, logs the mode and the
// ABOUTME: cap once at startup (no identity id), and declares the refusal counter.
package io.github.stevdrey.dokene.tenant.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProvisioningProperties.class)
class ProvisioningConfiguration {
    static final String REFUSED = "dokene.provisioning.refused";

    private static final Logger log = LoggerFactory.getLogger(ProvisioningConfiguration.class);

    ProvisioningConfiguration(ProvisioningProperties properties) {
        int cap = properties.maxWorkspacesPerIdentity();
        switch (properties.mode()) {
            case DISABLED -> log.info(
                    "Workspace provisioning mode: disabled; max workspaces per identity: {}", cap);
            case ALLOWLIST -> log.info(
                    "Workspace provisioning mode: allowlist ({} listed identities); max workspaces per identity: {}",
                    properties.allowedIdentities().size(), cap);
            case OPEN -> log.warn(
                    "Workspace provisioning mode: open (every signed in identity may provision); "
                            + "max workspaces per identity: {}", cap);
        }
    }

    @Bean
    ProvisioningRefusalCounter provisioningRefusalCounter(ObjectProvider<MeterRegistry> registry) {
        MeterRegistry meterRegistry = registry.getIfAvailable();
        if (meterRegistry == null) {
            return reason -> {
            };
        }
        return reason -> meterRegistry.counter(REFUSED, "reason", reason.tag()).increment();
    }
}
