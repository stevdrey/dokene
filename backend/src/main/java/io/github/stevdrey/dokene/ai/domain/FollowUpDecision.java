package io.github.stevdrey.dokene.ai.domain;

import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.followup.domain.FollowUpEvaluation;
import io.github.stevdrey.dokene.followup.domain.FollowUpReason;
import io.github.stevdrey.dokene.followup.domain.FollowUpStatus;
import io.github.stevdrey.dokene.followup.domain.FollowUpTimingSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Composite application contract representing the Next Best Action decision for a customer.
 * <p>
 * Strictly separates authoritative deterministic state (evaluated from application policies,
 * consent, and purchase signals) from untrusted advisory AI recommendation output.
 * <p>
 * Under no circumstance can model output override deterministic eligibility or authorize an action
 * for an ineligible customer.
 *
 * @param customerId Authoritative customer identifier (Deterministic)
 * @param eligible Authoritative eligibility status (Deterministic: true only if status is DUE or OVERDUE)
 * @param followUpStatus Authoritative follow-up due status (Deterministic)
 * @param eligibilityReasons Authoritative eligibility / ineligibility reasons (Deterministic)
 * @param tenantDate Authoritative tenant calendar date at evaluation (Deterministic)
 * @param tenantZone Authoritative tenant time zone (Deterministic)
 * @param evaluatedAt Timestamp when deterministic evaluation occurred (Deterministic)
 * @param nextFollowUpDate Target follow-up date, if any (Deterministic)
 * @param timingSource Authoritative source of cadence timing (Deterministic)
 * @param effectiveCadenceDays Effective cadence applied (Deterministic)
 * @param lastPurchaseAt Timestamp of latest valid purchase, if any (Deterministic)
 * @param recommendation Advisory AI recommendation outcome, if evaluated (Advisory / Untrusted)
 */
public record FollowUpDecision(
        CustomerId customerId,
        boolean eligible,
        FollowUpStatus followUpStatus,
        List<FollowUpReason> eligibilityReasons,
        LocalDate tenantDate,
        ZoneId tenantZone,
        Instant evaluatedAt,
        LocalDate nextFollowUpDate,
        FollowUpTimingSource timingSource,
        int effectiveCadenceDays,
        Instant lastPurchaseAt,
        RecommendationOutcome recommendation) {

    public FollowUpDecision {
        Objects.requireNonNull(customerId, "Customer ID is required");
        Objects.requireNonNull(followUpStatus, "Follow-up status is required");
        Objects.requireNonNull(tenantDate, "Tenant date is required");
        Objects.requireNonNull(tenantZone, "Tenant zone is required");
        Objects.requireNonNull(evaluatedAt, "Evaluation timestamp is required");
        Objects.requireNonNull(timingSource, "Timing source is required");
        Objects.requireNonNull(eligibilityReasons, "Eligibility reasons is required");
        eligibilityReasons = List.copyOf(eligibilityReasons);
        if (eligibilityReasons.isEmpty()) {
            throw new IllegalArgumentException("At least one eligibility reason is required");
        }

        boolean expectedEligible = (followUpStatus == FollowUpStatus.DUE || followUpStatus == FollowUpStatus.OVERDUE);
        if (eligible != expectedEligible) {
            throw new RecommendationValidationException("eligible",
                    "Eligible flag (" + eligible + ") must match status-derived eligibility (" + expectedEligible + ") for status " + followUpStatus);
        }

        // Invariant: An action recommendation can NEVER be assigned to an ineligible customer
        if (!eligible && recommendation instanceof ActionRecommendation) {
            throw new RecommendationValidationException("eligible",
                    "Cannot associate an action recommendation with a deterministically ineligible customer");
        }
    }

    /**
     * Factory for a customer who is deterministically ineligible for follow-up.
     * Guarantees that no advisory action can be scheduled or recommended.
     */
    public static FollowUpDecision ineligible(FollowUpEvaluation evaluation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        if (evaluation.eligible()) {
            throw new IllegalArgumentException("Customer is deterministically eligible; use recommended() or noRecommendation()");
        }
        return new FollowUpDecision(
                evaluation.customerId(),
                false,
                evaluation.status(),
                evaluation.reasons(),
                evaluation.tenantDate(),
                evaluation.tenantZone(),
                evaluation.evaluatedAt(),
                evaluation.nextFollowUpDate(),
                evaluation.timingSource(),
                evaluation.effectiveCadenceDays(),
                evaluation.lastPurchaseAt(),
                null);
    }

    /**
     * Factory for an eligible customer with an advisory action recommendation from the model.
     */
    public static FollowUpDecision recommended(FollowUpEvaluation evaluation, ActionRecommendation recommendation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(recommendation, "Recommendation is required");
        if (!evaluation.eligible()) {
            throw new RecommendationValidationException("eligible",
                    "Cannot recommend action for deterministically ineligible customer");
        }
        return new FollowUpDecision(
                evaluation.customerId(),
                true,
                evaluation.status(),
                evaluation.reasons(),
                evaluation.tenantDate(),
                evaluation.tenantZone(),
                evaluation.evaluatedAt(),
                evaluation.nextFollowUpDate(),
                evaluation.timingSource(),
                evaluation.effectiveCadenceDays(),
                evaluation.lastPurchaseAt(),
                recommendation);
    }

    /**
     * Factory for a customer where the model evaluated context and produced an explicit no-recommendation / refusal.
     */
    public static FollowUpDecision noRecommendation(FollowUpEvaluation evaluation, NoRecommendation noRecommendation) {
        Objects.requireNonNull(evaluation, "Evaluation is required");
        Objects.requireNonNull(noRecommendation, "No-recommendation outcome is required");
        return new FollowUpDecision(
                evaluation.customerId(),
                evaluation.eligible(),
                evaluation.status(),
                evaluation.reasons(),
                evaluation.tenantDate(),
                evaluation.tenantZone(),
                evaluation.evaluatedAt(),
                evaluation.nextFollowUpDate(),
                evaluation.timingSource(),
                evaluation.effectiveCadenceDays(),
                evaluation.lastPurchaseAt(),
                noRecommendation);
    }

    public Optional<RecommendationOutcome> advisoryRecommendation() {
        return Optional.ofNullable(recommendation);
    }

    public boolean hasActionRecommendation() {
        return recommendation instanceof ActionRecommendation;
    }
}
