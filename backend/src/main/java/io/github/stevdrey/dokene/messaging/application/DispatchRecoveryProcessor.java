// ABOUTME: Dispatch recovery (issue 138, ADR 0028 §5): turns STARTED claims older than the claim timeout into unknown
// ABOUTME: outcomes under the authorizing member's re-validated context. Never calls the provider and never resends.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.customer.application.CustomerRepository;
import io.github.stevdrey.dokene.customer.domain.Customer;
import io.github.stevdrey.dokene.customer.domain.CustomerId;
import io.github.stevdrey.dokene.messaging.application.OutboundMessageRepository.ExpiredClaimRef;
import io.github.stevdrey.dokene.messaging.domain.MessageStatus;
import io.github.stevdrey.dokene.messaging.domain.OutboundMessage;
import io.github.stevdrey.dokene.messaging.domain.ProviderSendResult;
import io.github.stevdrey.dokene.messaging.domain.SendAttempt;
import io.github.stevdrey.dokene.messaging.domain.SendAttemptOutcome;
import io.github.stevdrey.dokene.messaging.domain.Transition;
import io.github.stevdrey.dokene.tenant.application.CurrentMembershipAuthorizer;
import io.github.stevdrey.dokene.tenant.application.TenantAccessDeniedException;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A claim the inline dispatch could not complete (crash, outcome transaction failure) keeps its message SENDING and
 * blocks every further send. Once older than the claim timeout, the provider call it guarded has certainly ended, so
 * the honest record is an unknown outcome (T11) for a human to resolve (T12). The provider is never asked: any call
 * here could duplicate a message that was in fact delivered.
 *
 * <p>Each claim is recorded as the member who authorized it, as the inline outcome would have been (ADR 0028 §6), but
 * only while that membership is still active and still holds {@code MESSAGE_SEND}: checked first to build the context,
 * then again inside the transaction under the customer lock. Otherwise, or when the outcome still cannot be recorded,
 * the claim stays as it is and only ids are logged.
 *
 * <p>Claims left in place stay STARTED, so each run continues after the last attempt id the previous run saw and wraps
 * around at the end. Every expired claim is reached within a few runs, however many of them cannot be recorded.
 */
@Service
@EnableConfigurationProperties(DispatchRecoveryProperties.class)
public class DispatchRecoveryProcessor {
    private static final Logger log = LoggerFactory.getLogger(DispatchRecoveryProcessor.class);
    private static final ProviderSendResult UNKNOWN = new ProviderSendResult.OutcomeUnknown(Optional.empty());
    /**
     * Slack beyond the dispatch deadline: a claim's start time is stamped before its commit and the provider call, and
     * the inline outcome is recorded after the deadline, so a claim only just older than the deadline may still be live.
     */
    static final Duration MIN_CLAIM_TIMEOUT_MARGIN = Duration.ofMinutes(1);

    private final OutboundMessageRepository messages;
    private final CustomerRepository customers;
    private final TenantContextProvider contexts;
    private final CurrentMembershipAuthorizer currentMembers;
    private final AuditExecutionContext auditExecution;
    private final MessageAuditPort audit;
    private final DispatchRecoveryProperties properties;
    private final MessagingDispatchProperties dispatch;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final AtomicReference<UUID> cursor = new AtomicReference<>();

    public DispatchRecoveryProcessor(OutboundMessageRepository messages, CustomerRepository customers,
            TenantContextProvider contexts,
            CurrentMembershipAuthorizer currentMembers, AuditExecutionContext auditExecution, MessageAuditPort audit,
            DispatchRecoveryProperties properties, MessagingDispatchProperties dispatch,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.messages = Objects.requireNonNull(messages);
        this.customers = Objects.requireNonNull(customers);
        this.contexts = Objects.requireNonNull(contexts);
        this.currentMembers = Objects.requireNonNull(currentMembers);
        this.auditExecution = Objects.requireNonNull(auditExecution);
        this.audit = Objects.requireNonNull(audit);
        this.properties = Objects.requireNonNull(properties);
        this.dispatch = Objects.requireNonNull(dispatch);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
        Duration minimum = dispatch.sendTimeout().plus(MIN_CLAIM_TIMEOUT_MARGIN);
        if (properties.enabled() && properties.claimTimeout().compareTo(minimum) < 0) {
            // A younger claim may still guard a provider call in flight; recording it as unknown would race it.
            throw new IllegalStateException("dokene.messaging.recovery.claim-timeout (" + properties.claimTimeout()
                    + ") must be at least dokene.messaging.send-timeout (" + dispatch.sendTimeout() + ") plus "
                    + MIN_CLAIM_TIMEOUT_MARGIN);
        }
    }

    /** Scheduling is on application wide (DokeneApplication); this property is the only switch for recovery. */
    @Scheduled(fixedDelayString = "${dokene.messaging.recovery.interval:PT1M}",
            initialDelayString = "${dokene.messaging.recovery.interval:PT1M}")
    void scheduledRun() {
        if (!properties.enabled()) {
            return;
        }
        try {
            recoverExpiredClaims();
        } catch (RuntimeException exception) {
            log.error("Dispatch recovery run failed; errorType={}", exception.getClass().getSimpleName());
        }
    }

    /**
     * One bounded run. Returns how many claims were recorded as unknown outcomes, or 0 without doing anything while
     * another instance runs: one runner at a time keeps instances from resolving and locking the same claims.
     */
    public int recoverExpiredClaims() {
        if (contexts.currentTenantId().isPresent()) {
            throw new IllegalStateException("Dispatch recovery must start without a tenant scope");
        }
        return messages.runAsSoleRecoveryRunner(this::runBatch).orElseGet(() -> {
            log.debug("Dispatch recovery run skipped; another instance is running");
            return 0;
        });
    }

    private int runBatch() {
        Instant cutoff = clock.instant().minus(properties.claimTimeout());
        List<ExpiredClaimRef> expired = messages.findExpiredClaims(cutoff, Optional.ofNullable(cursor.get()),
                properties.batchSize());
        // A short page ends the pass; the next run starts again from the lowest attempt id.
        cursor.set(expired.size() < properties.batchSize() ? null : expired.getLast().attemptId());
        int recovered = 0;
        for (ExpiredClaimRef ref : expired) {
            try {
                if (recover(ref, cutoff)) {
                    recovered++;
                }
            } catch (RuntimeException exception) {
                // The transaction rolled back, so the claim is untouched and the next run tries again.
                log.error("Expired claim not recovered; claim kept; messageId={}, tenantId={}, errorType={}",
                        ref.messageId(), ref.tenantId(), exception.getClass().getSimpleName());
            }
        }
        if (!expired.isEmpty()) {
            log.info("Dispatch recovery run; expired={}, recovered={}", expired.size(), recovered);
        }
        return recovered;
    }

    private boolean recover(ExpiredClaimRef ref, Instant cutoff) {
        TenantId tenantId = new TenantId(ref.tenantId());
        Optional<SendAttempt> claim = contexts.callWithTenantId(tenantId, () -> expiredClaim(ref, cutoff));
        if (claim.isEmpty()) {
            return false;
        }
        Optional<TenantContext> member = authorizingMember(tenantId, claim.get());
        if (member.isEmpty()) {
            log.warn("Expired claim kept; authorizing membership no longer allows sending; messageId={}, "
                    + "tenantId={}, membershipId={}", ref.messageId(), ref.tenantId(),
                    claim.get().requestedByMembershipId());
            return false;
        }
        return auditExecution.callWithCorrelation(UUID.randomUUID(), () -> contexts.callWithContext(member.get(),
                () -> Boolean.TRUE.equals(transactions.execute(status -> record(member.get(), claim.get())))));
    }

    /** The listed attempt, if still STARTED and older than the cutoff (an unlocked read; re-checked later). */
    private Optional<SendAttempt> expiredClaim(ExpiredClaimRef ref, Instant cutoff) {
        return started(ref.tenantId(), ref.messageId()).filter(attempt -> attempt.id().equals(ref.attemptId()))
                .filter(attempt -> attempt.startedAt().isBefore(cutoff));
    }

    /**
     * The authorizing membership, still active and holding MESSAGE_SEND, checked without auditing so a claim that stays
     * blocked writes no denial row on every run. The transaction checks it again, audited, under the customer lock.
     */
    private Optional<TenantContext> authorizingMember(TenantId tenantId, SendAttempt claim) {
        return currentMembers.currentMember(new IdentityId(claim.requestedByActorId()), tenantId,
                new TenantMembershipId(claim.requestedByMembershipId()), TenantPermission.MESSAGE_SEND);
    }

    /** T11 under the customer then message locks, the order every send and delivery transition takes. */
    private boolean record(TenantContext context, SendAttempt claim) {
        UUID tenantId = context.tenantId().value();
        UUID messageId = claim.messageId();
        OutboundMessage unlocked = messages.findById(tenantId, messageId).orElse(null);
        Customer customer = unlocked == null ? null
                : customers.findByIdForUpdate(context.tenantId(), new CustomerId(unlocked.customerId())).orElse(null);
        if (customer == null) {
            log.warn("Expired claim kept; message or customer not readable in the tenant; messageId={}, tenantId={}",
                    messageId, tenantId);
            return false;
        }
        try {
            // Re-read under the customer lock: a revocation or demotion since the check above stops the recovery.
            currentMembers.requireResourceAccess(TenantPermission.MESSAGE_SEND, customer);
        } catch (TenantAccessDeniedException exception) {
            log.warn("Expired claim kept; authorizing membership no longer allows sending; messageId={}, tenantId={}",
                    messageId, tenantId);
            return false;
        }
        OutboundMessage message = messages.findByIdForUpdate(tenantId, messageId).orElse(null);
        // Another run or the late inline outcome may have completed the claim while this one waited for the locks.
        boolean stillClaimed = message != null && message.status() == MessageStatus.SENDING
                && !message.outcomeUnknown()
                && started(tenantId, messageId).map(SendAttempt::id).filter(claim.id()::equals).isPresent();
        if (!stillClaimed) {
            log.info("Expired claim already completed; messageId={}, tenantId={}", messageId, tenantId);
            return false;
        }
        Instant now = clock.instant();
        Transition unknown = message.completeAttempt(UNKNOWN, now, dispatch.maxSendAttempts());
        messages.update(message, unknown.next());
        messages.appendEvent(unknown.event());
        messages.updateAttempt(claim.completed(UNKNOWN, now));
        audit.sendOutcomeUnknown(unknown.event());
        log.warn("Expired claim recorded as unknown outcome; messageId={}, tenantId={}, attempt={}", messageId,
                tenantId, claim.attemptNumber());
        return true;
    }

    private Optional<SendAttempt> started(UUID tenantId, UUID messageId) {
        return messages.findAttempts(tenantId, messageId).stream()
                .filter(attempt -> attempt.outcome() == SendAttemptOutcome.STARTED).findFirst();
    }
}
