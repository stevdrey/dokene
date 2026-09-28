package io.github.stevdrey.dokene.ai.provider.disabled;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Condition that matches when no concrete AI provider (such as {@code fake} or {@code openai})
 * is configured via {@code dokene.ai.provider}.
 */
public final class NoAiProviderCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String provider = context.getEnvironment().getProperty("dokene.ai.provider");
        return provider == null
                || provider.isBlank()
                || (!"fake".equalsIgnoreCase(provider.trim()) && !"openai".equalsIgnoreCase(provider.trim()));
    }
}
