package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiResilience;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.AiRecommendationRequest;
import io.github.stevdrey.dokene.ai.application.AiRecommendationResponse;
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
import java.util.concurrent.atomic.AtomicReference;
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
    private final AiProvider rawProvider;
    private final AiResilience resilience;
    private final RecommendationContextAssembler assembler;
    private final AiActionGate gate;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final FollowUpService followUps;
    private final FollowUpRecommendationRateLimiter rateLimiter;
    private final AiOutcomeReporter reporter;

    @Autowired
    public FollowUpRecommendationService(
            AiProvider provider,
            RecommendationContextAssembler assembler,
            AiActionGate gate,
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            FollowUpService followUps,
            FollowUpRecommendationRateLimiter rateLimiter,
            AiResilience resilience,
            AiOutcomeReporter reporter) {
        Objects.requireNonNull(provider, "AI provider is required");
        this.rawProvider = provider;
        this.resilience = resilience;
        this.provider = resilience == null ? provider : resilience.wrap(provider);
        this.reporter = reporter == null ? AiOutcomeReporter.noop() : reporter;
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
            FollowUpService followUps,
            FollowUpRecommendationRateLimiter rateLimiter) {
        this(provider, assembler, gate, authorization, contexts, followUps, rateLimiter, null, null);
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
        Invocation invocation = invoke(customerId, timeout);
        if (invocation.aiInvoked()) {
            reportOutcome(customerId, invocation.decision(), invocation.metadata());
        }
        return invocation.decision();
    }

    /**
     * Result of one orchestration step. {@code aiInvoked} is false for the deterministic-ineligible short-circuit,
     * which never reaches the provider and therefore has no AI outcome to report. {@code metadata} is the validated
     * metadata of the provider response (null when no provider was reached); it labels the terminal outcome metric.
     */
    private record Invocation(FollowUpDecision decision, boolean aiInvoked, AiInvocationMetadata metadata) {
    }

    /**
     * Runs the provider and the Action Gate without reporting the final outcome, so the caller can report it once
     * the result is decided (a result discarded by a later version check must not be reported as delivered).
     * Rejections that abort the request with an exception are reported here because no result will follow.
     */
    private Invocation invoke(CustomerId customerId, Duration timeout) {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(timeout, "Timeout is required");
        var assembly = assembler.assemble(customerId);
        var evaluation = assembly.evaluation();
        if (!evaluation.eligible()) {
            if (gate != null) {
                gate.revalidateAuthorization(customerId);
            }
            return new Invocation(FollowUpDecision.ineligible(evaluation, assembly.policyVersion()), false, null);
        }
        if (rateLimiter != null && contexts != null) {
            var tenantContext = contexts.requireCurrent();
            rateLimiter.acquire(tenantContext.tenantId(), tenantContext.identityId());
        }
        AiRecommendationRequest request = new AiRecommendationRequest(AiOperation.NEXT_BEST_ACTION,
                assembly.context(), timeout);
        AiRecommendationResponse response;
        AtomicReference<AiInvocationMetadata> lastAttempt = new AtomicReference<>();
        try {
            response = invocationProvider(customerId, lastAttempt).recommend(request);
        } catch (TenantAccessDeniedException | CustomerNotFoundException ex) {
            reportBoundaryRejection(customerId, ex, lastAttempt.get());
            throw ex;
        }
        RecommendationOutcome outcome = response.outcome();

        ActionGateDecision gateDecision = gate.evaluate(customerId, assembly, outcome);
        if (gateDecision.rejectionReason().isPresent()) {
            ActionGateRejectionReason reason = gateDecision.rejectionReason().get();
            if (reason == ActionGateRejectionReason.NO_TENANT_CONTEXT || reason == ActionGateRejectionReason.UNAUTHORIZED) {
                reporter.gateRejected(customerId, AiOperation.NEXT_BEST_ACTION, reason, response.metadata());
                throw new TenantAccessDeniedException("Authorization revoked or tenant context unavailable");
            }
            if (reason == ActionGateRejectionReason.CUSTOMER_NOT_FOUND) {
                reporter.gateRejected(customerId, AiOperation.NEXT_BEST_ACTION, reason, response.metadata());
                throw new CustomerNotFoundException();
            }
        }

        FollowUpEvaluation effectiveEvaluation = gateDecision.evaluation().orElse(evaluation);
        if (gateDecision instanceof ActionGateDecision.Accepted accepted) {
            return new Invocation(FollowUpDecision.accepted(effectiveEvaluation, accepted), true, response.metadata());
        }
        return new Invocation(FollowUpDecision.rejected(effectiveEvaluation, gateDecision), true, response.metadata());
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

        Duration effectiveTimeout = resolveTimeout(requestedTimeout);

        long policyVersion = 0L;
        if (followUps != null) {
            var policy = followUps.customerPolicy(customerId);
            if (policy != null) {
                policyVersion = policy.version();
                if (expectedVersion != null && expectedVersion != policyVersion) {
                    throw new FollowUpConflictException();
                }
            }
        }

        try {
            Invocation invocation = invoke(customerId, effectiveTimeout);
            FollowUpDecision decision = invocation.decision();
            FollowUpEvaluation evaluation = decision.evaluation();
            Long gateVersion = decision.gateDecision().policyVersion();
            long currentVersion = resolvePolicyVersion(customerId, policyVersion);
            long freshVersion = gateVersion != null ? gateVersion : currentVersion;

            if (expectedVersion != null && gateVersion != null && !expectedVersion.equals(gateVersion)) {
                reportDiscarded(customerId, invocation);
                throw new FollowUpConflictException();
            }

            if (gateVersion != null && currentVersion != gateVersion.longValue()) {
                reportDiscarded(customerId, invocation);
                return FollowUpRecommendationResult.staleState(evaluation, currentVersion);
            }

            if (invocation.aiInvoked()) {
                reportOutcome(customerId, decision, invocation.metadata());
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
                    if (evaluation != null && !evaluation.eligible()) {
                        ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(evaluation);
                        return FollowUpRecommendationResult.ineligible(evaluation, ineligibilityReason, freshVersion);
                    }
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
            return unavailable(customerId, policyVersion, expectedVersion, AiUnavailableReason.from(ex.category()),
                    ex.metadata());
        } catch (RecommendationContextException ex) {
            // context assembly failed before any provider was reached: provider/model are reported as "none"
            return unavailable(customerId, policyVersion, expectedVersion, AiUnavailableReason.from(ex.reason()), null);
        }
    }

    /**
     * Degrades to a safe result when the AI step cannot produce advice. Authorization is revalidated first,
     * the failure is reported (metrics, log, audit) and the deterministic evaluation is returned unchanged.
     */
    private FollowUpRecommendationResult unavailable(
            CustomerId customerId,
            long policyVersion,
            Long expectedVersion,
            AiUnavailableReason reason,
            AiInvocationMetadata metadata) {
        revalidateReportingBoundaryRejection(customerId, metadata);
        reporter.failed(customerId, AiOperation.NEXT_BEST_ACTION, reason, metadata);
        FollowUpService.FollowUpEvaluationSnapshot snapshot = resolveFallbackSnapshot(customerId, policyVersion);
        FollowUpEvaluation eval = snapshot.evaluation();
        long freshVersion = snapshot.policyVersion();
        if (expectedVersion != null && expectedVersion != freshVersion) {
            throw new FollowUpConflictException();
        }
        if (freshVersion != policyVersion) {
            return FollowUpRecommendationResult.staleState(eval, freshVersion);
        }
        if (eval != null && !eval.eligible()) {
            ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(eval);
            return FollowUpRecommendationResult.ineligible(eval, ineligibilityReason, freshVersion);
        }
        return FollowUpRecommendationResult.aiUnavailable(eval, reason, freshVersion);
    }

    /**
     * The provider for one request. When resilience is configured, authorization is re-validated before every
     * retry so a retry can never send customer context after the caller's access was revoked or the customer
     * disappeared.
     */
    private AiProvider invocationProvider(CustomerId customerId, AtomicReference<AiInvocationMetadata> lastAttempt) {
        if (resilience == null || gate == null) {
            return provider;
        }
        return resilience.wrap(rawProvider, previousAttempt -> {
            lastAttempt.set(previousAttempt);
            gate.revalidateAuthorization(customerId);
        });
    }

    /** Raised by the pre-retry guard: the provider was already called once, so the invocation is still reported. */
    private void reportBoundaryRejection(CustomerId customerId, RuntimeException ex, AiInvocationMetadata lastAttempt) {
        reporter.gateRejected(customerId, AiOperation.NEXT_BEST_ACTION, ex instanceof CustomerNotFoundException
                ? ActionGateRejectionReason.CUSTOMER_NOT_FOUND : ActionGateRejectionReason.UNAUTHORIZED, lastAttempt);
    }

    /**
     * Revalidation can abort the failure path (authorization revoked, customer gone). The invocation already
     * happened, so its terminal outcome must still be counted and logged before the exception propagates; the
     * reporter suppresses the audit row for these tenant-boundary reasons.
     */
    private void revalidateReportingBoundaryRejection(CustomerId customerId, AiInvocationMetadata metadata) {
        if (gate == null) {
            return;
        }
        try {
            gate.revalidateAuthorization(customerId);
        } catch (TenantAccessDeniedException ex) {
            reporter.gateRejected(customerId, AiOperation.NEXT_BEST_ACTION, ActionGateRejectionReason.UNAUTHORIZED,
                    metadata);
            throw ex;
        } catch (CustomerNotFoundException ex) {
            reporter.gateRejected(customerId, AiOperation.NEXT_BEST_ACTION,
                    ActionGateRejectionReason.CUSTOMER_NOT_FOUND, metadata);
            throw ex;
        }
    }

    private void reportOutcome(CustomerId customerId, FollowUpDecision decision, AiInvocationMetadata metadata) {
        if (decision.rejectionReason().isPresent()) {
            reporter.gateRejected(customerId, AiOperation.NEXT_BEST_ACTION, decision.rejectionReason().get(), metadata);
        } else if (decision.recommendation() instanceof NoRecommendation) {
            reporter.modelRefused(customerId, AiOperation.NEXT_BEST_ACTION, metadata);
        } else {
            reporter.generated(customerId, AiOperation.NEXT_BEST_ACTION, metadata);
        }
    }

    /** The output was produced but not delivered because the customer policy moved: audit it as a stale rejection. */
    private void reportDiscarded(CustomerId customerId, Invocation invocation) {
        if (invocation.aiInvoked()) {
            reporter.gateRejected(customerId, AiOperation.NEXT_BEST_ACTION, ActionGateRejectionReason.STALE_STATE,
                    invocation.metadata());
        }
    }

    private FollowUpService.FollowUpEvaluationSnapshot resolveFallbackSnapshot(
            CustomerId customerId,
            long policyVersion) {
        if (followUps == null) {
            return new FollowUpService.FollowUpEvaluationSnapshot(null, policyVersion);
        }
        try {
            FollowUpService.FollowUpEvaluationSnapshot snapshot = followUps.evaluateSnapshot(customerId);
            if (snapshot != null) {
                return snapshot;
            }
        } catch (Exception ignored) {
            // fall back to separate calls if evaluateSnapshot throws in unconfigured mocks
        }
        FollowUpEvaluation eval = followUps.evaluate(customerId);
        long freshVersion = resolvePolicyVersion(customerId, policyVersion);
        return new FollowUpService.FollowUpEvaluationSnapshot(eval, freshVersion);
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
        if (requested == null) {
            return defaultTimeout;
        }
        if (requested.isNegative() || requested.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return requested.compareTo(maxTimeout) > 0 ? maxTimeout : requested;
    }
}
