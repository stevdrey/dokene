// ABOUTME: One provider send attempt, committed before the provider call and completed after it.
// ABOUTME: The attempt number equals the message attempt count at the time it started.
package io.github.stevdrey.dokene.messaging.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SendAttempt(UUID id, UUID tenantId, UUID messageId, int attemptNumber, UUID sendKey,
        SendAttemptOutcome outcome, FailureCategory failureCategory, String providerMessageId,
        Instant startedAt, Instant finishedAt, UUID requestedByMembershipId, UUID requestedByActorId) {
    public SendAttempt {
        Objects.requireNonNull(id, "Attempt id is required");
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(messageId, "Message id is required");
        Objects.requireNonNull(sendKey, "Send key is required");
        Objects.requireNonNull(outcome, "Outcome is required");
        Objects.requireNonNull(startedAt, "Start time is required");
        Objects.requireNonNull(requestedByMembershipId, "Membership id is required");
        Objects.requireNonNull(requestedByActorId, "Actor id is required");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number starts at 1");
        }
        if ((finishedAt == null) != (outcome == SendAttemptOutcome.STARTED)) {
            throw new IllegalArgumentException("Exactly STARTED attempts have no finish time");
        }
        boolean failed = outcome == SendAttemptOutcome.FAILED_PERMANENT || outcome == SendAttemptOutcome.FAILED_TRANSIENT;
        if ((failureCategory != null) != failed) {
            throw new IllegalArgumentException("Exactly failed attempts carry a failure category");
        }
        if (providerMessageId != null && outcome != SendAttemptOutcome.ACCEPTED
                && outcome != SendAttemptOutcome.OUTCOME_UNKNOWN) {
            throw new IllegalArgumentException("Only accepted or unknown attempts carry a provider message id");
        }
    }

    /** The row committed before the provider call (T7). */
    public static SendAttempt started(OutboundMessage message, UUID requestedByMembershipId,
            UUID requestedByActorId, Instant now) {
        return new SendAttempt(UUID.randomUUID(), message.tenantId(), message.id(), message.attemptCount(),
                message.sendKey(), SendAttemptOutcome.STARTED, null, null, now, null,
                requestedByMembershipId, requestedByActorId);
    }

    /** The same row after the provider answered (T8 to T11). */
    public SendAttempt completed(ProviderSendResult result, Instant now) {
        if (outcome != SendAttemptOutcome.STARTED) {
            throw new IllegalStateException("Attempt already completed");
        }
        return switch (result) {
            case ProviderSendResult.Accepted accepted -> with(SendAttemptOutcome.ACCEPTED, null,
                    accepted.providerMessageId(), now);
            case ProviderSendResult.RejectedPermanently rejected -> with(SendAttemptOutcome.FAILED_PERMANENT,
                    rejected.category(), null, now);
            case ProviderSendResult.FailedTransiently failed -> with(SendAttemptOutcome.FAILED_TRANSIENT,
                    failed.category(), null, now);
            case ProviderSendResult.OutcomeUnknown unknown -> with(SendAttemptOutcome.OUTCOME_UNKNOWN, null,
                    unknown.providerMessageId().orElse(null), now);
        };
    }

    private SendAttempt with(SendAttemptOutcome newOutcome, FailureCategory category, String providerId,
            Instant finished) {
        return new SendAttempt(id, tenantId, messageId, attemptNumber, sendKey, newOutcome, category, providerId,
                startedAt, finished, requestedByMembershipId, requestedByActorId);
    }
}
