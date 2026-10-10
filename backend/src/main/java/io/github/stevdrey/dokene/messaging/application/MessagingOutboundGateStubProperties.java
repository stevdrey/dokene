// ABOUTME: Temporary stand in configuration for the outbound gate until feature 5 lands.
// ABOUTME: Closed by default so a fresh deployment refuses OUTBOUND_DISABLED_FOR_TENANT.
package io.github.stevdrey.dokene.messaging.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dokene.messaging.outbound-gate.stub")
public record MessagingOutboundGateStubProperties(boolean enabled) {
}
