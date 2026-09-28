package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.RecommendationContextException;
import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.RecommendationOutcome;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Duration;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Composes an authorized deterministic evaluation with optional advisory AI output,
 * strictly validated by the deterministic {@link AiActionGate}.
 */
@Service
public final class FollowUpRecommendationService {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final AiProvider provider;
    private final RecommendationContextAssembler assembler;
    private final AiActionGate gate;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final FollowUpService followUps;
    private final FollowUpRecommendationRateLimiter rateLimiter;

    @Autowired
    public FollowUpRecommendationService(
            AiProvider provider,
            RecommendationContextAssembler assembler,
            AiActionGate gate,
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            FollowUpService followUps,
            FollowUpRecommendationRateLimiter rateLimiter) {
        this.provider = Objects.requireNonNull(provider, "AI provider is required");
        this.assembler = Objects.requireNonNull(assembler, "Context assembler is required");
        this.gate = Objects.requireNonNull(gate, "AI action gate is required");
        this.authorization = authorization;
        this.contexts = contexts;
        this.followUps = followUps;
        this.rateLimiter = rateLimiter;
    }

    public FollowUpRecommendationService(
            AiProvider provider,
            RecommendationContextAssembler assembler,
            AiActionGate gate,
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            FollowUpService followUps) {
        this(provider, assembler, gate, authorization, contexts, followUps, null);
    }

    public FollowUpRecommendationService(
            AiProvider provider,
            RecommendationContextAssembler assembler,
            AiActionGate gate) {
        this(provider, assembler, gate, null, null, null, null);
    }

    public FollowUpDecision recommend(CustomerId customerId, Duration timeout) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        var assembly = assembler.assemble(customerId);
        var evaluation = assembly.evaluation();
        if (!evaluation.eligible()) {
            return FollowUpDecision.ineligible(evaluation, assembly.policyVersion());
        }
        if (rateLimiter != null && contexts != null) {
            var tenantContext = contexts.requireCurrent();
            rateLimiter.acquire(tenantContext.tenantId(), tenantContext.identityId());
        }
        AiRecommendationRequest request = new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION,
                assembly.context(), timeout);
        RecommendationOutcome outcome = provider.recommend(request).outcome();

        ActionGateDecision gateDecision = gate.evaluate(customerId, assembly, outcome);
        if (gateDecision.rejectionReason().isPresent()) {
            ActionGateRejectionReason reason = gateDecision.rejectionReason().get();
            if (reason == ActionGateRejectionReason.NO_TENANT_CONTEXT || reason == ActionGateRejectionReason.UNAUTHORIZED) {
                throw new TenantAccessDeniedException("Authorization revoked or tenant context unavailable");
            }
            if (reason == ActionGateRejectionReason.CUSTOMER_NOT_FOUND) {
                throw new CustomerNotFoundException();
            }
        }

        FollowUpEvaluation effectiveEvaluation = gateDecision.evaluation().orElse(evaluation);
        if (gateDecision instanceof ActionGateDecision.Accepted accepted) {
            return FollowUpDecision.accepted(effectiveEvaluation, accepted);
        }
        return FollowUpDecision.rejected(effectiveEvaluation, gateDecision);
    }

    public FollowUpDecision recommend(FollowUpEvaluation evaluation, Duration timeout) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        return recommend(evaluation.customerId(), timeout);
    }

    /**
     * Safe orchestration method for customer recommendation requests.
     * Validates tenant authorization, verifies optimistic policy version if supplied,
     * normalizes AI provider errors without unhandled exceptions, and maps
     * into a typed {@link FollowUpRecommendationResult}.
     */
    public FollowUpRecommendationResult recommendSafe(
            CustomerId customerId,
            Duration requestedTimeout,
            Long expectedVersion) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        if (contexts != null) {
            contexts.requireCurrent();
        }
        if (authorization != null) {
            authorization.requirePermission(TenantPermission.FOLLOWUP_EVALUATE);
        }

        long policyVersion = 0L;
        if (followUps != null) {
            var policy = followUps.customerPolicy(customerId);
            policyVersion = policy.version();
            if (expectedVersion != null && expectedVersion != policyVersion) {
                throw new FollowUpConflictException();
            }
        }

        Duration effectiveTimeout = resolveTimeout(requestedTimeout);

        try {
            FollowUpDecision decision = recommend(customerId, effectiveTimeout);
            FollowUpEvaluation evaluation = decision.evaluation();
            long gateVersion = decision.gateDecision().policyVersion();
            long currentVersion = resolvePolicyVersion(customerId, policyVersion);
            long freshVersion = gateVersion > 0L ? gateVersion : currentVersion;

            if (expectedVersion != null && gateVersion > 0L && expectedVersion != gateVersion) {
                throw new FollowUpConflictException();
            }

            if (gateVersion > 0L && currentVersion != gateVersion) {
                return FollowUpRecommendationResult.staleState(evaluation, currentVersion);
            }

            if (decision.gateDecision().isAccepted()) {
                if (decision.recommendation() instanceof ActionRecommendation action) {
                    return FollowUpRecommendationResult.available(evaluation, action, freshVersion);
                } else if (decision.recommendation() instanceof NoRecommendation refusal) {
                    if (!evaluation.eligible()) {
                        ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(evaluation);
                        return FollowUpRecommendationResult.ineligible(evaluation, ineligibilityReason, freshVersion);
                    }
                    return FollowUpRecommendationResult.refusal(evaluation, refusal, freshVersion);
                }
            }

            // Gate rejection or deterministic ineligibility
            if (decision.rejectionReason().isPresent()) {
                ActionGateRejectionReason reason = decision.rejectionReason().get();
                if (reason == ActionGateRejectionReason.NO_TENANT_CONTEXT || reason == ActionGateRejectionReason.UNAUTHORIZED) {
                    throw new TenantAccessDeniedException("Authorization revoked or tenant context unavailable");
                }
                if (reason == ActionGateRejectionReason.CUSTOMER_NOT_FOUND) {
                    throw new CustomerNotFoundException();
                }
                if (reason == ActionGateRejectionReason.STALE_STATE) {
                    return FollowUpRecommendationResult.staleState(evaluation, freshVersion);
                }
                if (reason == ActionGateRejectionReason.DISALLOWED_ACTION
                        || reason == ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT
                        || reason == ActionGateRejectionReason.INVALID_RECOMMENDATION) {
                    return FollowUpRecommendationResult.aiUnavailable(evaluation, reason, freshVersion);
                }
                return FollowUpRecommendationResult.ineligible(evaluation, reason, freshVersion);
            }
            return FollowUpRecommendationResult.ineligible(
                    evaluation, ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE, freshVersion);

        } catch (AiProviderException ex) {
            if (gate != null) {
                gate.revalidateAuthorization(customerId);
            }
            FollowUpEvaluation eval = followUps != null ? followUps.evaluate(customerId) : null;
            long freshVersion = resolvePolicyVersion(customerId, policyVersion);
            if (expectedVersion != null && freshVersion > 0L && expectedVersion != freshVersion) {
                throw new FollowUpConflictException();
            }
            if (eval != null && !eval.eligible()) {
                ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(eval);
                return FollowUpRecommendationResult.ineligible(eval, ineligibilityReason, freshVersion);
            }
            return FollowUpRecommendationResult.aiUnavailable(eval, ex.category().name(), freshVersion);
        } catch (RecommendationContextException ex) {
            if (gate != null) {
                gate.revalidateAuthorization(customerId);
            }
            FollowUpEvaluation eval = followUps != null ? followUps.evaluate(customerId) : null;
            long freshVersion = resolvePolicyVersion(customerId, policyVersion);
            if (expectedVersion != null && freshVersion > 0L && expectedVersion != freshVersion) {
                throw new FollowUpConflictException();
            }
            if (eval != null && !eval.eligible()) {
                ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(eval);
                return FollowUpRecommendationResult.ineligible(eval, ineligibilityReason, freshVersion);
            }
            return FollowUpRecommendationResult.aiUnavailable(
                    eval,
                    "CONTEXT_" + ex.reason().name(),
                    freshVersion);
        }
    }

    private ActionGateRejectionReason deriveIneligibilityReason(FollowUpEvaluation evaluation) {
        if (evaluation != null && evaluation.reasons() != null) {
            if (evaluation.reasons().contains(FollowUpReason.DO_NOT_CONTACT)) {
                return ActionGateRejectionReason.DO_NOT_CONTACT;
            }
            if (evaluation.reasons().contains(FollowUpReason.NO_ELIGIBLE_CONTACT)) {
                return ActionGateRejectionReason.NO_CONTACT_CONSENT;
            }
            if (evaluation.reasons().contains(FollowUpReason.CUSTOMER_ARCHIVED)) {
                return ActionGateRejectionReason.CUSTOMER_ARCHIVED;
            }
        }
        return ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE;
    }

    private long resolvePolicyVersion(CustomerId customerId, long fallbackVersion) {
        if (followUps == null) {
            return fallbackVersion;
        }
        try {
            return followUps.customerPolicy(customerId).version();
        } catch (Exception ex) {
            return fallbackVersion;
        }
    }

    private Duration resolveTimeout(Duration requested) {
        Duration defaultTimeout = provider != null ? provider.defaultTimeout() : DEFAULT_TIMEOUT;
        Duration maxTimeout = provider != null ? provider.maxTimeout() : MAX_TIMEOUT;
        if (requested == null || requested.isNegative() || requested.isZero()) {
            return defaultTimeout;
        }
        return requested.compareTo(maxTimeout) > 0 ? maxTimeout : requested;
    }
}
