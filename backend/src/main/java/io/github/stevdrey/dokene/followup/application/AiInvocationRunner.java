package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
import io.github.stevdrey.dokene.ai.application.AiResilience;
import io.github.stevdrey.dokene.ai.application.RetryGuard;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import java.util.Objects;
import java.util.function.Function;

/**
 * The single path from a follow-up AI service to the provider (ADR 0035). In order, it:
 * <ol>
 *   <li>takes a permit from the AI rate limit, so an exhausted tenant's refusals are still throttled;</li>
 *   <li>opens the tenant's monthly token budget, which refuses with {@link AiTokenBudgetExhaustedException} before any
 *       provider call (skipped for a disabled provider, which bills nothing);</li>
 *   <li>wraps the raw provider with the meter <em>inside</em> the retry decorator, so every attempt's usage counts;</li>
 *   <li>runs the call and writes the counted usage once.</li>
 * </ol>
 * An architecture rule keeps every other follow-up class from calling the provider directly, so a new AI operation
 * cannot skip the budget. The operations are typed methods rather than a callback, so no provider call is compiled
 * into a caller.
 */
final class AiInvocationRunner {
    private final AiProvider rawProvider;
    private final AiResilience resilience;
    private final FollowUpRecommendationRateLimiter rateLimiter;
    private final AiTokenBudget budget;

    AiInvocationRunner(AiProvider rawProvider, AiResilience resilience, FollowUpRecommendationRateLimiter rateLimiter,
            AiTokenBudget budget) {
        this.rawProvider = Objects.requireNonNull(rawProvider, "AI provider is required");
        this.resilience = resilience;
        this.rateLimiter = rateLimiter;
        this.budget = budget;
    }

    /**
     * @param tenantContext the active tenant, or null when the caller has none (unit-test wiring only); without it
     *                      neither the rate limit nor the budget applies
     * @param guard         runs before every retry; {@link RetryGuard#none()} when there is nothing to re-validate
     */
    AiRecommendationResponse recommend(TenantContext tenantContext, RetryGuard guard, AiRecommendationRequest request) {
        Objects.requireNonNull(request, "Request is required");
        return run(tenantContext, guard, provider -> provider.recommend(request));
    }

    /** As {@link #recommend}, for a message draft. */
    AiDraftResponse draft(TenantContext tenantContext, RetryGuard guard, AiDraftRequest request) {
        Objects.requireNonNull(request, "Request is required");
        return run(tenantContext, guard, provider -> provider.draft(request));
    }

    private <R> R run(TenantContext tenantContext, RetryGuard guard, Function<AiProvider, R> call) {
        Objects.requireNonNull(guard, "Retry guard is required");
        Objects.requireNonNull(call, "Provider call is required");
        if (rateLimiter != null && tenantContext != null) {
            rateLimiter.acquire(tenantContext.tenantId(), tenantContext.identityId());
        }
        AiTokenBudget.Meter meter = budget != null && tenantContext != null && rawProvider.enabled()
                ? budget.open(tenantContext.tenantId()) : AiTokenBudget.unmetered();
        AiProvider metered = meter.wrap(rawProvider);
        AiProvider composed = resilience == null ? metered : resilience.wrap(metered, guard);
        return meter.call(() -> call.apply(composed));
    }
}
