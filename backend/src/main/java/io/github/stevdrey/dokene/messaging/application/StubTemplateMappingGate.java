// ABOUTME: Property driven TemplateMappingGate stand in; reports MAPPED only for listed intents.
// ABOUTME: Feature 6 replaces this bean with the tenant template catalogue.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.messaging.domain.MessagingLocales;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(MessagingTemplateGateStubProperties.class)
public class StubTemplateMappingGate implements TemplateMappingGate {
    /** The one parameter the stand in template takes: the stored body (spec 0003, step 9). */
    static final List<String> PARAMETER_NAMES = List.of("body");

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

    /** The intent name in lower case, the one supported locale as language, and ["body"]. */
    @Override
    public Optional<ResolvedTemplate> resolve(SemanticTemplateIntent intent) {
        if (check(intent) != TemplateMappingStatus.MAPPED) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedTemplate(intent.name().toLowerCase(Locale.ROOT),
                MessagingLocales.SUPPORTED.iterator().next(), PARAMETER_NAMES));
    }
}
