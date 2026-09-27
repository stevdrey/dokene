package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.ActionRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendation;
import io.github.stevdrey.dokene.ai.domain.NoRecommendationReason;
import io.github.stevdrey.dokene.ai.domain.RecommendationConfidence;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.util.Objects;
import java.util.Optional;

/**
 * Normalized result of a Next Best Action recommendation request, combining
 * authoritative evaluation facts with optional advisory AI advice and gate decisions.
 */
public record FollowUpRecommendationResult(
        RecommendationStatus status,
        FollowUpEvaluation evaluation,
        ActionRecommendation recommendation,
        NoRecommendation refusal,
        ActionGateRejectionReason rejectionReason,
        String unavailableReason,
        long policyVersion) {

    public FollowUpRecommendationResult {
        Objects.requireNonNull(status, "Status is required");
        Objects.requireNonNull(evaluation, "Evaluation is required");
    }

    public static FollowUpRecommendationResult available(
            FollowUpEvaluation evaluation,
            ActionRecommendation recommendation,
            long policyVersion) {
        Objects.requireNonNull(recommendation, "Recommendation is required");
        return new FollowUpRecommendationResult(
                RecommendationStatus.AVAILABLE,
                evaluation,
                recommendation,
                null,
                null,
                null,
                policyVersion);
    }

    public static FollowUpRecommendationResult refusal(
            FollowUpEvaluation evaluation,
            NoRecommendation refusal,
            long policyVersion) {
        Objects.requireNonNull(refusal, "Refusal is required");
        return new FollowUpRecommendationResult(
                RecommendationStatus.NO_RECOMMENDATION,
                evaluation,
                null,
                refusal,
                null,
                null,
                policyVersion);
    }

    public static FollowUpRecommendationResult refusal(
            FollowUpEvaluation evaluation,
            NoRecommendationReason refusalReason,
            long policyVersion) {
        Objects.requireNonNull(refusalReason, "Refusal reason is required");
        return refusal(evaluation,
                new NoRecommendation(refusalReason, "No follow-up action is recommended", RecommendationConfidence.of(1.0)),
                policyVersion);
    }

    public static FollowUpRecommendationResult ineligible(
            FollowUpEvaluation evaluation,
            ActionGateRejectionReason rejectionReason,
            long policyVersion) {
        return new FollowUpRecommendationResult(
                RecommendationStatus.INELIGIBLE,
                evaluation,
                null,
                null,
                rejectionReason != null ? rejectionReason : ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE,
                null,
                policyVersion);
    }

    public static FollowUpRecommendationResult staleState(
            FollowUpEvaluation evaluation,
            long policyVersion) {
        return new FollowUpRecommendationResult(
                RecommendationStatus.STALE_STATE,
                evaluation,
                null,
                null,
                ActionGateRejectionReason.STALE_STATE,
                null,
                policyVersion);
    }

    public static FollowUpRecommendationResult aiUnavailable(
            FollowUpEvaluation evaluation,
            ActionGateRejectionReason rejectionReason,
            long policyVersion) {
        Objects.requireNonNull(rejectionReason, "Rejection reason is required");
        return new FollowUpRecommendationResult(
                RecommendationStatus.AI_UNAVAILABLE,
                evaluation,
                null,
                null,
                rejectionReason,
                rejectionReason.name(),
                policyVersion);
    }

    public static FollowUpRecommendationResult aiUnavailable(
            FollowUpEvaluation evaluation,
            String unavailableReason,
            long policyVersion) {
        Objects.requireNonNull(unavailableReason, "Unavailable reason is required");
        return new FollowUpRecommendationResult(
                RecommendationStatus.AI_UNAVAILABLE,
                evaluation,
                null,
                null,
                null,
                unavailableReason,
                policyVersion);
    }

    public NoRecommendationReason refusalReason() {
        return refusal != null ? refusal.reason() : null;
    }

    public Optional<ActionRecommendation> advisoryRecommendation() {
        return Optional.ofNullable(recommendation);
    }

    public Optional<NoRecommendation> advisoryRefusal() {
        return Optional.ofNullable(refusal);
    }

    public Optional<NoRecommendationReason> explicitRefusal() {
        return Optional.ofNullable(refusalReason());
    }

    public Optional<ActionGateRejectionReason> gateRejection() {
        return Optional.ofNullable(rejectionReason);
    }

    public Optional<String> providerFailure() {
        return Optional.ofNullable(unavailableReason);
    }
}
