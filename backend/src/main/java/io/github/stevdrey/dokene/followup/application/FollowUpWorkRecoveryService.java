// ABOUTME: Authorized, audited and idempotent recovery of exhausted follow-up work (issue 461, ADR 0038): list the items
// ABOUTME: awaiting a decision, then requeue a safe failure or resolve any item. Never sends or replays an unknown one.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditTarget;
import io.github.stevdrey.dokene.followup.domain.FollowUpRecoverableWork;
import io.github.stevdrey.dokene.followup.domain.FollowUpWorkRecoveryAction;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs in the calling member's tenant context (an HTTP request), under forced RLS, in one transaction with its audit
 * row: a failed audit write rolls the decision back. A decision names the item's recovery count as the version it
 * expects; repeating a decision that was already applied at that version returns the item without a second change or
 * audit row.
 */
@Service
public class FollowUpWorkRecoveryService {
    public static final int MAX_LIST_LIMIT = 100;
    private static final Logger log = LoggerFactory.getLogger(FollowUpWorkRecoveryService.class);

    private final FollowUpWorkLedgerRepository ledger;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final AuditRecorder audit;
    private final FollowUpRetryPolicy retries;
    private final Clock clock;

    public FollowUpWorkRecoveryService(FollowUpWorkLedgerRepository ledger, TenantAuthorizationService authorization,
            TenantContextProvider contexts, AuditRecorder audit, FollowUpRetryPolicy retries, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger);
        this.authorization = Objects.requireNonNull(authorization);
        this.contexts = Objects.requireNonNull(contexts);
        this.audit = Objects.requireNonNull(audit);
        this.retries = Objects.requireNonNull(retries);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Why a recovery decision was refused; safe to return to the caller. */
    public enum Refusal {
        /** The item's recovery count is not the expected one: another decision came first. */
        STALE_VERSION,
        /** The item does not await recovery (it is pending, claimed, due for a retry or done). */
        NOT_EXHAUSTED,
        /** An external effect may have happened, so the item may only be resolved. */
        OUTCOME_UNKNOWN,
        /** The item was requeued as often as {@code dokene.follow-up.retry.max-requeues} allows. */
        REQUEUE_LIMIT_REACHED,
        /** The customer has other open follow-up work, or a newer cycle has superseded this one. */
        CUSTOMER_HAS_NEWER_WORK
    }

    /** The decision could not be applied; nothing was written. */
    public static final class RecoveryRefusedException extends RuntimeException {
        private final Refusal refusal;

        public RecoveryRefusedException(Refusal refusal) {
            super("Follow-up work recovery refused: " + refusal);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** No recoverable item with that id exists in the current tenant. */
    public static final class RecoverableWorkNotFoundException extends RuntimeException {
        public RecoverableWorkNotFoundException() {
            super("Follow-up work item not found");
        }
    }

    /** @param applied {@code false} when the same decision had already been applied at the expected version */
    public record Recovery(FollowUpRecoverableWork item, boolean applied) {
    }

    /** The current tenant's oldest {@code limit} exhausted items awaiting a decision. */
    @Transactional(readOnly = true)
    public List<FollowUpRecoverableWork> recoverable(int limit) {
        authorization.requirePermission(TenantPermission.FOLLOWUP_READ);
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and " + MAX_LIST_LIMIT);
        }
        return ledger.findRecoverable(tenant(), limit);
    }

    /**
     * Applies {@code action} to the item when its recovery count is {@code expectedRecoveryCount}.
     *
     * @throws RecoverableWorkNotFoundException when the current tenant has no such item
     * @throws RecoveryRefusedException when the item's state does not allow the decision
     */
    @Transactional
    public Recovery recover(UUID candidateId, FollowUpWorkRecoveryAction action, int expectedRecoveryCount) {
        authorization.requirePermission(TenantPermission.FOLLOWUP_WRITE);
        Objects.requireNonNull(candidateId, "Candidate id is required");
        Objects.requireNonNull(action, "Recovery action is required");
        if (expectedRecoveryCount < 0) {
            throw new IllegalArgumentException("Expected recovery count must not be negative");
        }
        TenantId tenantId = tenant();
        FollowUpRecoverableWork item = ledger.lockRecoverable(tenantId, candidateId)
                .orElseThrow(RecoverableWorkNotFoundException::new);
        if (item.recoveryCount() == expectedRecoveryCount + 1 && item.lastRecoveryAction().filter(action::equals).isPresent()) {
            return new Recovery(item, false);
        }
        if (item.recoveryCount() != expectedRecoveryCount) {
            throw new RecoveryRefusedException(Refusal.STALE_VERSION);
        }
        if (!item.awaitingRecovery()) {
            throw new RecoveryRefusedException(Refusal.NOT_EXHAUSTED);
        }
        switch (action) {
            case REQUEUE -> requeue(tenantId, item);
            case RESOLVE -> {
                if (!ledger.resolve(tenantId, candidateId, expectedRecoveryCount, clock.instant())) {
                    throw new IllegalStateException("A locked exhausted item could not be resolved");
                }
            }
        }
        audit.followUpMutated(AuditTarget.Type.CUSTOMER, item.customerId().value(),
                action == FollowUpWorkRecoveryAction.REQUEUE ? AuditEventType.FOLLOW_UP_WORK_REQUEUED
                        : AuditEventType.FOLLOW_UP_WORK_RESOLVED);
        log.info("Follow-up work recovered; tenantId={}, candidateId={}, action={}, lastFailure={}",
                tenantId.value(), candidateId, action, item.lastFailure());
        return new Recovery(ledger.lockRecoverable(tenantId, candidateId).orElseThrow(), true);
    }

    private void requeue(TenantId tenantId, FollowUpRecoverableWork item) {
        if (item.outcomeUnknown()) {
            throw new RecoveryRefusedException(Refusal.OUTCOME_UNKNOWN);
        }
        if (item.recoveryCount() >= retries.properties().maxRequeues()) {
            throw new RecoveryRefusedException(Refusal.REQUEUE_LIMIT_REACHED);
        }
        boolean requeued;
        try {
            requeued = ledger.requeue(tenantId, item.candidateId(), item.recoveryCount(), clock.instant());
        } catch (DuplicateKeyException concurrentEnqueue) {
            // An enqueue opened newer work for the customer after the check; the transaction is rolled back.
            throw new RecoveryRefusedException(Refusal.CUSTOMER_HAS_NEWER_WORK);
        }
        if (!requeued) {
            throw new RecoveryRefusedException(Refusal.CUSTOMER_HAS_NEWER_WORK);
        }
    }

    private TenantId tenant() {
        return contexts.requireCurrent().tenantId();
    }
}
