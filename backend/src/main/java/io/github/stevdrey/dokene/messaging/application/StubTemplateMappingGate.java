// ABOUTME: Property driven TemplateMappingGate stand in; reports MAPPED only for listed intents.
// ABOUTME: Feature 6 replaces this bean with the tenant template catalogue.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(MessagingTemplateGateStubProperties.class)
public class StubTemplateMappingGate implements TemplateMappingGate {
    private final MessagingTemplateGateStubProperties properties;

    public StubTemplateMappingGate(MessagingTemplateGateStubProperties properties) {
        this.properties = Objects.requireNonNull(properties, "Template gate properties are required");
    }

    @Override
    public TemplateMappingStatus check(SemanticTemplateIntent intent) {
        Objects.requireNonNull(intent, "Template intent is required");
        return properties.enabledIntents().contains(intent) ? TemplateMappingStatus.MAPPED
                : TemplateMappingStatus.NOT_MAPPED;
    }
}
