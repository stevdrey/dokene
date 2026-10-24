// ABOUTME: Deployment switch for writing en-US messages (issue 268): off unless set, read once at startup. Shipping the
// ABOUTME: ability to read en-US before enabling writes keeps a rollback to the previous release safe until it is on.
package io.github.stevdrey.dokene.messaging.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Expand first: this release always reads {@code en-US} messages, but submits them only while {@code enabled}. Turn it
 * on once every instance runs this release; until then no {@code en-US} row exists, so an earlier release, which
 * cannot read one, can still be rolled back to. Unset or blank binds to {@code null}, which means off.
 */
@ConfigurationProperties(prefix = "dokene.messaging.english")
public record EnglishOutboundProperties(Boolean enabled) {
    public EnglishOutboundProperties {
        enabled = enabled != null && enabled;
    }
}
