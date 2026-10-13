// ABOUTME: Temporary stand in configuration for the template gate until feature 6 lands.
// ABOUTME: Empty by default so submit fails closed with TEMPLATE_NOT_MAPPED.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dokene.messaging.template-gate.stub")
public record MessagingTemplateGateStubProperties(Set<SemanticTemplateIntent> enabledIntents) {
    public MessagingTemplateGateStubProperties {
        enabledIntents = enabledIntents == null ? Set.of() : Set.copyOf(enabledIntents);
    }
}
