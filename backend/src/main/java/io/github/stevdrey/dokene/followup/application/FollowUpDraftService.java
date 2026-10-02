package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.application.AiCompletionStatus;
import io.github.stevdrey.dokene.ai.application.AiFailureCategory;
import io.github.stevdrey.dokene.ai.application.AiInvocationMetadata;
import io.github.stevdrey.dokene.ai.application.AiDraftRequest;
import io.github.stevdrey.dokene.ai.application.AiDraftResponse;
import io.github.stevdrey.dokene.ai.application.AiOperation;
import io.github.stevdrey.dokene.ai.application.AiProvider;
import io.github.stevdrey.dokene.ai.application.AiResilience;
import io.github.stevdrey.dokene.ai.application.AiProviderException;
import io.github.stevdrey.dokene.ai.application.DraftContext;
import io.github.stevdrey.dokene.ai.application.RecommendationContextException;
import io.github.stevdrey.dokene.ai.application.TrustedBusinessFacts;
import io.github.stevdrey.dokene.ai.domain.DraftOutcome;
import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.SemanticAction;
import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.customer.application.CustomerNotFoundException;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Orchestrates constrained follow-up message draft generation.
 * Enforces MESSAGE_DRAFT permission, grounds output in authoritative context,
 * and revalidates all drafts through the deterministic AiActionGate.
 */
@Service
public final class FollowUpDraftService {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MAX_TIMEOUT = Duration.ofSeconds(30);

    private final AiProvider provider;
    private final RecommendationContextAssembler assembler;
    private final AiActionGate gate;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final FollowUpService followUps;
    private final TenantRepository tenants;
    private final FollowUpRecommendationRateLimiter rateLimiter;
    private final AiOutcomeReporter reporter;

    @Autowired
    public FollowUpDraftService(
            AiProvider provider,
            RecommendationContextAssembler assembler,
            AiActionGate gate,
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            FollowUpService followUps,
            TenantRepository tenants,
            FollowUpRecommendationRateLimiter rateLimiter,
            AiResilience resilience,
            AiOutcomeReporter reporter) {
        Objects.requireNonNull(provider, "AI provider is required");
        this.provider = resilience == null ? provider : resilience.wrap(provider);
        this.reporter = reporter == null ? AiOutcomeReporter.noop() : reporter;
        this.assembler = Objects.requireNonNull(assembler, "Context assembler is required");
        this.gate = Objects.requireNonNull(gate, "AI action gate is required");
        this.authorization = authorization;
        this.contexts = contexts;
        this.followUps = followUps;
        this.tenants = tenants;
        this.rateLimiter = rateLimiter;
    }

    public FollowUpDraftService(
            AiProvider provider,
            RecommendationContextAssembler assembler,
            AiActionGate gate,
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            FollowUpService followUps,
            TenantRepository tenants,
            FollowUpRecommendationRateLimiter rateLimiter) {
        this(provider, assembler, gate, authorization, contexts, followUps, tenants, rateLimiter, null, null);
    }

    public FollowUpDraftService(
            AiProvider provider,
            RecommendationContextAssembler assembler,
            AiActionGate gate,
            TenantAuthorizationService authorization,
            TenantContextProvider contexts,
            FollowUpService followUps,
            TenantRepository tenants) {
        this(provider, assembler, gate, authorization, contexts, followUps, tenants, null);
    }

    public FollowUpDraftService(
            AiProvider provider,
            RecommendationContextAssembler assembler,
            AiActionGate gate) {
        this(provider, assembler, gate, null, null, null, null, null);
    }

    /**
     * Safe orchestration method for customer draft requests.
     */
    public FollowUpDraftResult draftSafe(
            CustomerId customerId,
            SemanticAction requestedAction,
            SemanticTemplateIntent requestedTemplateIntent,
            Duration requestedTimeout,
            Long expectedVersion) {
        Objects.requireNonNull(customerId, "Customer ID is required");

        var tenantContext = contexts != null ? contexts.requireCurrent() : null;
        if (authorization != null) {
            authorization.requirePermission(TenantPermission.MESSAGE_DRAFT);
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
            var assembly = assembler.assemble(customerId);
            var evaluation = assembly.evaluation();
            if (assembly.policyVersion() != null) {
                policyVersion = assembly.policyVersion();
                if (expectedVersion != null && expectedVersion != policyVersion) {
                    throw new FollowUpConflictException();
                }
            }

            if (!evaluation.eligible()) {
                if (gate != null) {
                    gate.revalidateDraftAuthorization(customerId);
                }
                ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(evaluation);
                return FollowUpDraftResult.ineligible(evaluation, ineligibilityReason, policyVersion);
            }

            // Determine target action
            List<SemanticAction> allowedActions = assembly.context().trusted().allowedActions();
            SemanticAction action = requestedAction;
            if (action != null) {
                if (!allowedActions.contains(action)
                        || (action == SemanticAction.REPEAT_PURCHASE_FOLLOW_UP && assembly.purchases().isEmpty())) {
                    revalidateAuthorization(customerId);
                    return FollowUpDraftResult.ineligible(evaluation, ActionGateRejectionReason.DISALLOWED_ACTION, policyVersion);
                }
            } else {
                boolean hasPurchaseHistory = !assembly.purchases().isEmpty();
                if (!hasPurchaseHistory && allowedActions.contains(SemanticAction.GENERAL_CHECK_IN)) {
                    action = SemanticAction.GENERAL_CHECK_IN;
                } else {
                    action = allowedActions.contains(SemanticAction.REPEAT_PURCHASE_FOLLOW_UP)
                            ? SemanticAction.REPEAT_PURCHASE_FOLLOW_UP
                            : allowedActions.getFirst();
                }
            }

            // Determine target template intent
            SemanticTemplateIntent templateIntent = requestedTemplateIntent;
            if (templateIntent != null) {
                if (!DraftContext.isCompatibleIntent(action, templateIntent)) {
                    revalidateAuthorization(customerId);
                    throw new IllegalArgumentException("Template intent is incompatible with the requested action");
                }
            } else {
                templateIntent = resolveDefaultIntent(action);
            }

            // Ground with business facts
            String businessName = "Dokene";
            if (tenants != null && tenantContext != null) {
                var tenantOpt = tenants.findById(tenantContext.tenantId());
                if (tenantOpt.isPresent()) {
                    businessName = tenantOpt.get().displayName();
                }
            }
            TrustedBusinessFacts businessFacts = new TrustedBusinessFacts(businessName);
            DraftContext draftContext = new DraftContext(assembly.context(), action, templateIntent, businessFacts);

            // Fail closed before customer data crosses the provider boundary
            revalidateAuthorization(customerId);

            // Rate limiter quota
            if (rateLimiter != null && tenantContext != null) {
                rateLimiter.acquire(tenantContext.tenantId(), tenantContext.identityId());
            }

            AiDraftRequest request = new AiDraftRequest(draftContext, effectiveTimeout);
            AiDraftResponse response = provider.draft(request);
            if (response == null || response.metadata() == null
                    || response.metadata().status() != AiCompletionStatus.SUCCEEDED) {
                throw new AiProviderException(AiFailureCategory.UNAVAILABLE,
                        response != null && response.metadata() != null ? response.metadata()
                                : new AiInvocationMetadata("unknown", "unknown", "unknown", Duration.ZERO, null, AiCompletionStatus.FAILED));
            }
            DraftOutcome outcome = response.outcome();

            DraftGateDecision gateDecision = gate.evaluateDraft(customerId, assembly, outcome, action, templateIntent, businessFacts.preferredLocale());
            reportOutcome(customerId, gateDecision, outcome);
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
            Long gateVersion = gateDecision.policyVersion();
            long currentVersion = resolvePolicyVersion(customerId, policyVersion);
            long freshVersion = gateVersion != null ? gateVersion : currentVersion;

            if (expectedVersion != null && gateVersion != null && !expectedVersion.equals(gateVersion)) {
                throw new FollowUpConflictException();
            }

            if (gateVersion != null && currentVersion != gateVersion.longValue()) {
                return FollowUpDraftResult.staleState(effectiveEvaluation, currentVersion);
            }

            if (gateDecision.isAccepted()) {
                if (gateDecision.rawOutcome().orElse(null) instanceof MessageDraft draft) {
                    return FollowUpDraftResult.available(effectiveEvaluation, draft, freshVersion);
                } else if (gateDecision.rawOutcome().orElse(null) instanceof NoDraft refusal) {
                    if (!effectiveEvaluation.eligible()) {
                        ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(effectiveEvaluation);
                        return FollowUpDraftResult.ineligible(effectiveEvaluation, ineligibilityReason, freshVersion);
                    }
                    return FollowUpDraftResult.refusal(effectiveEvaluation, refusal, freshVersion);
                }
            }

            // Gate rejection
            if (gateDecision.rejectionReason().isPresent()) {
                ActionGateRejectionReason reason = gateDecision.rejectionReason().get();
                if (reason == ActionGateRejectionReason.STALE_STATE) {
                    if (!effectiveEvaluation.eligible()) {
                        ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(effectiveEvaluation);
                        return FollowUpDraftResult.ineligible(effectiveEvaluation, ineligibilityReason, freshVersion);
                    }
                    return FollowUpDraftResult.staleState(effectiveEvaluation, freshVersion);
                }
                if (reason == ActionGateRejectionReason.DISALLOWED_ACTION
                        || reason == ActionGateRejectionReason.DISALLOWED_TEMPLATE_INTENT
                        || reason == ActionGateRejectionReason.INVALID_RECOMMENDATION) {
                    return FollowUpDraftResult.aiUnavailable(effectiveEvaluation, reason, freshVersion);
                }
                return FollowUpDraftResult.ineligible(effectiveEvaluation, reason, freshVersion);
            }

            return FollowUpDraftResult.ineligible(
                    effectiveEvaluation, ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE, freshVersion);

        } catch (AiProviderException ex) {
            return unavailable(customerId, policyVersion, expectedVersion, AiUnavailableReason.from(ex.category()));
        } catch (UnsupportedOperationException ex) {
            return unavailable(customerId, policyVersion, expectedVersion, AiUnavailableReason.NOT_AVAILABLE);
        } catch (RecommendationContextException ex) {
            return unavailable(customerId, policyVersion, expectedVersion, AiUnavailableReason.from(ex.reason()));
        }
    }

    /**
     * Degrades to a safe result when the AI step cannot produce a draft. Authorization is revalidated first,
     * the failure is reported (metrics, log, audit) and the deterministic evaluation is returned unchanged.
     */
    private FollowUpDraftResult unavailable(
            CustomerId customerId,
            long policyVersion,
            Long expectedVersion,
            AiUnavailableReason reason) {
        if (gate != null) {
            gate.revalidateDraftAuthorization(customerId);
        }
        reporter.failed(customerId, AiOperation.MESSAGE_DRAFT, reason);
        FollowUpService.FollowUpEvaluationSnapshot snapshot = resolveFallbackSnapshot(customerId, policyVersion);
        FollowUpEvaluation eval = snapshot.evaluation();
        long freshVersion = snapshot.policyVersion();
        if (expectedVersion != null && expectedVersion != freshVersion) {
            throw new FollowUpConflictException();
        }
        if (freshVersion != policyVersion) {
            return FollowUpDraftResult.staleState(eval, freshVersion);
        }
        if (eval != null && !eval.eligible()) {
            ActionGateRejectionReason ineligibilityReason = deriveIneligibilityReason(eval);
            return FollowUpDraftResult.ineligible(eval, ineligibilityReason, freshVersion);
        }
        return FollowUpDraftResult.aiUnavailable(eval, reason, freshVersion);
    }

    private void reportOutcome(CustomerId customerId, DraftGateDecision decision, DraftOutcome outcome) {
        if (decision.rejectionReason().isPresent()) {
            reporter.gateRejected(customerId, AiOperation.MESSAGE_DRAFT, decision.rejectionReason().get());
        } else if (outcome instanceof NoDraft) {
            reporter.modelRefused(customerId, AiOperation.MESSAGE_DRAFT);
        } else {
            reporter.generated(customerId, AiOperation.MESSAGE_DRAFT);
        }
    }

    private SemanticTemplateIntent resolveDefaultIntent(SemanticAction action) {
        return switch (action) {
            case REPEAT_PURCHASE_FOLLOW_UP -> SemanticTemplateIntent.REPEAT_PURCHASE;
            case GENERAL_CHECK_IN -> SemanticTemplateIntent.GENERAL_FOLLOW_UP;
            case RELATED_PRODUCT_OFFER -> SemanticTemplateIntent.RELATED_PRODUCT;
            case DORMANT_REENGAGEMENT -> SemanticTemplateIntent.DORMANT_CUSTOMER;
            case SEASONAL_GREETING -> SemanticTemplateIntent.SEASONAL_EVENT;
        };
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
        }
        FollowUpEvaluation eval = followUps.evaluate(customerId);
        long freshVersion = resolvePolicyVersion(customerId, policyVersion);
        return new FollowUpService.FollowUpEvaluationSnapshot(eval, freshVersion);
    }

    private void revalidateAuthorization(CustomerId customerId) {
        if (gate != null) {
            gate.revalidateDraftAuthorization(customerId);
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
        if (requested == null) {
            return defaultTimeout;
        }
        if (requested.isNegative() || requested.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        return requested.compareTo(maxTimeout) > 0 ? maxTimeout : requested;
    }
}
