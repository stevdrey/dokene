// ABOUTME: The global outbound kill switch (ADR 0023 §3.3, spec 0008): one deployment property, off unless set, bound
// ABOUTME: once at startup so no route, tenant row or header can turn sending on while the process runs.
package io.github.stevdrey.dokene.messaging.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Unset or blank binds to {@code null}, which means off; changing the value needs a restart. */
@ConfigurationProperties(prefix = "dokene.messaging.outbound")
public record OutboundSwitchProperties(Boolean enabled) {
    public OutboundSwitchProperties {
        enabled = enabled != null && enabled;
    }
}
