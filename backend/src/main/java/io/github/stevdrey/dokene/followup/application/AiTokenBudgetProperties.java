package io.github.stevdrey.dokene.followup.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-tenant AI token budget (ADR 0035). One deployment-wide limit applies to every tenant separately: the total of
 * provider-reported input and output tokens a tenant may consume in one UTC calendar month. Only the deployment
 * operator can change it; tenant members can neither see nor change it.
 *
 * @param monthlyTokens tokens per tenant per UTC calendar month; absent means {@link #DEFAULT_MONTHLY_TOKENS}. A
 *                      value of zero or less is a configuration error and fails startup, so a typo can never
 *                      silently switch the budget off.
 */
@ConfigurationProperties(prefix = "dokene.ai.token-budget")
public record AiTokenBudgetProperties(Long monthlyTokens) {
    public static final long DEFAULT_MONTHLY_TOKENS = 2_000_000L;

    public AiTokenBudgetProperties {
        if (monthlyTokens == null) {
            monthlyTokens = DEFAULT_MONTHLY_TOKENS;
        }
        if (monthlyTokens <= 0) {
            throw new IllegalArgumentException("dokene.ai.token-budget.monthly-tokens must be positive");
        }
    }

    public static AiTokenBudgetProperties defaults() {
        return new AiTokenBudgetProperties(DEFAULT_MONTHLY_TOKENS);
    }
}
