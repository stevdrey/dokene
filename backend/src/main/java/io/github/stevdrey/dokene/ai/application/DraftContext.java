package io.github.stevdrey.dokene.ai.application;

import io.github.stevdrey.dokene.ai.domain.RecommendationValidationException;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import java.util.Objects;

/**
 * Provider-bound context for drafting a follow-up message.
 * Binds authoritative customer context, validated action, template intent, and trusted business facts.
 */
public record DraftContext(
        RecommendationContext customerContext,
        SemanticAction action,
        SemanticTemplateIntent templateIntent,
        TrustedBusinessFacts businessFacts) {

    public DraftContext {
        Objects.requireNonNull(customerContext, "Customer context is required");
        Objects.requireNonNull(action, "Semantic action is required");
        Objects.requireNonNull(templateIntent, "Semantic template intent is required");
        Objects.requireNonNull(businessFacts, "Trusted business facts are required");

        if (customerContext.trusted() == null
                || customerContext.trusted().allowedActions() == null
                || !customerContext.trusted().allowedActions().contains(action)) {
            throw new RecommendationValidationException("action",
                    "Action '" + action + "' is not permitted in customer context");
        }
        if (!isCompatibleIntent(action, templateIntent)) {
            throw new RecommendationValidationException("templateIntent",
                    "Template intent '" + templateIntent + "' is not compatible with action '" + action + "'");
        }
    }

    public static boolean isCompatibleIntent(SemanticAction action, SemanticTemplateIntent intent) {
        if (action == null || intent == null) {
            return false;
        }
        return switch (action) {
            case REPEAT_PURCHASE_FOLLOW_UP -> intent == SemanticTemplateIntent.REPEAT_PURCHASE
                    || intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case GENERAL_CHECK_IN -> intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case RELATED_PRODUCT_OFFER -> intent == SemanticTemplateIntent.RELATED_PRODUCT
                    || intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case DORMANT_REENGAGEMENT -> intent == SemanticTemplateIntent.DORMANT_CUSTOMER
                    || intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case SEASONAL_GREETING -> intent == SemanticTemplateIntent.SEASONAL_EVENT
                    || intent == SemanticTemplateIntent.GENERAL_FOLLOW_UP;
        };
    }
}
