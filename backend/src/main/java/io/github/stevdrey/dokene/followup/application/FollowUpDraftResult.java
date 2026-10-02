package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.ai.domain.MessageDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraft;
import io.github.stevdrey.dokene.ai.domain.NoDraftReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import java.util.Objects;
import java.util.Optional;

/**
 * Normalized result of a follow-up draft generation request.
 */
public record FollowUpDraftResult(
        DraftStatus status,
        FollowUpEvaluation evaluation,
        MessageDraft draft,
        NoDraft refusal,
        ActionGateRejectionReason rejectionReason,
        String unavailableReason,
        long policyVersion) {

    public FollowUpDraftResult {
        Objects.requireNonNull(status, "Status is required");
    }

    public static FollowUpDraftResult available(
            FollowUpEvaluation evaluation,
            MessageDraft draft,
            long policyVersion) {
        Objects.requireNonNull(draft, "Message draft is required");
        return new FollowUpDraftResult(
                DraftStatus.AVAILABLE,
                evaluation,
                draft,
                null,
                null,
                null,
                policyVersion);
    }

    public static FollowUpDraftResult refusal(
            FollowUpEvaluation evaluation,
            NoDraft refusal,
            long policyVersion) {
        Objects.requireNonNull(refusal, "NoDraft refusal is required");
        return new FollowUpDraftResult(
                DraftStatus.NO_DRAFT,
                evaluation,
                null,
                refusal,
                null,
                null,
                policyVersion);
    }

    public static FollowUpDraftResult ineligible(
            FollowUpEvaluation evaluation,
            ActionGateRejectionReason rejectionReason,
            long policyVersion) {
        return new FollowUpDraftResult(
                DraftStatus.INELIGIBLE,
                evaluation,
                null,
                null,
                rejectionReason != null ? rejectionReason : ActionGateRejectionReason.FOLLOW_UP_INELIGIBLE,
                null,
                policyVersion);
    }

    public static FollowUpDraftResult staleState(
            FollowUpEvaluation evaluation,
            long policyVersion) {
        return new FollowUpDraftResult(
                DraftStatus.STALE_STATE,
                evaluation,
                null,
                null,
                ActionGateRejectionReason.STALE_STATE,
                null,
                policyVersion);
    }

    public static FollowUpDraftResult aiUnavailable(
            FollowUpEvaluation evaluation,
            ActionGateRejectionReason rejectionReason,
            long policyVersion) {
        Objects.requireNonNull(rejectionReason, "Rejection reason is required");
        return new FollowUpDraftResult(
                DraftStatus.AI_UNAVAILABLE,
                evaluation,
                null,
                null,
                rejectionReason,
                rejectionReason.name(),
                policyVersion);
    }

    public static FollowUpDraftResult aiUnavailable(
            FollowUpEvaluation evaluation,
            String unavailableReason,
            long policyVersion) {
        Objects.requireNonNull(unavailableReason, "Unavailable reason is required");
        return new FollowUpDraftResult(
                DraftStatus.AI_UNAVAILABLE,
                evaluation,
                null,
                null,
                null,
                unavailableReason,
                policyVersion);
    }

    public NoDraftReason refusalReason() {
        return refusal != null ? refusal.reason() : null;
    }

    public Optional<MessageDraft> advisoryDraft() {
        return Optional.ofNullable(draft);
    }

    public Optional<NoDraft> advisoryRefusal() {
        return Optional.ofNullable(refusal);
    }

    public Optional<NoDraftReason> explicitRefusal() {
        return Optional.ofNullable(refusalReason());
    }

    public Optional<ActionGateRejectionReason> gateRejection() {
        return Optional.ofNullable(rejectionReason);
    }

    public Optional<String> providerFailure() {
        return Optional.ofNullable(unavailableReason);
    }
}
