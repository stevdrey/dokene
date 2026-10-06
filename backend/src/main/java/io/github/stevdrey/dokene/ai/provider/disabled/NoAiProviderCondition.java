package io.github.stevdrey.dokene.ai.provider.disabled;

import io.github.stevdrey.dokene.ai.provider.AiProviderType;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches when no concrete AI provider (such as {@code fake} or {@code openai})
 * is configured via {@code dokene.ai.provider}. Unsupported values are rejected at startup by
 * {@code AiProviderSelectionConfiguration} rather than treated as unconfigured.
 */
public final class NoAiProviderCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return AiProviderType.fromProperty(context.getEnvironment().getProperty(AiProviderType.PROPERTY))
                == AiProviderType.DISABLED;
    }
}
