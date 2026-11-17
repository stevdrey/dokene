package io.github.stevdrey.dokene.audit.persistence.jdbc;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.application.DenialAuditBudget;
import io.github.stevdrey.dokene.audit.application.DenialAuditTelemetry;
import io.github.stevdrey.dokene.audit.domain.AiAuditDetail;
import io.github.stevdrey.dokene.audit.domain.AiAuditOperation;
import io.github.stevdrey.dokene.audit.domain.AiAuditOutcome;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.AuditOutcome;
import io.github.stevdrey.dokene.audit.domain.AuditTarget;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.tenant.application.DatabaseContextSigner;
import io.github.stevdrey.dokene.tenant.application.ProviderContextProvider;
import io.github.stevdrey.dokene.tenant.application.SignedDatabaseContext;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TransactionalAuditRecorder implements AuditRecorder {
    private static final Logger log = LoggerFactory.getLogger(TransactionalAuditRecorder.class);
    private final JdbcAuditStore store;
    private final TenantContextProvider contexts;
    private final ProviderContextProvider providerContexts;
    private final DatabaseContextSigner signer;
    private final AuditExecutionContext execution;
    private final Clock clock;
    private final TransactionTemplate independent;
    private final TransactionTemplate mandatory;
    private final DatabaseUnavailability databaseUnavailability;
    private final DenialAuditBudget denialBudget;
    private final DenialAuditTelemetry denialTelemetry;

    /** What became of one attempt to append a count of suppressed denials. */
    enum SummaryWrite { WRITTEN, ALREADY_WRITTEN, RETRY, REJECTED }

    TransactionalAuditRecorder(JdbcAuditStore store, TenantContextProvider contexts,
            ProviderContextProvider providerContexts, DatabaseContextSigner signer, AuditExecutionContext execution,
            Clock clock, PlatformTransactionManager transactions, DatabaseUnavailability databaseUnavailability,
            DenialAuditBudget denialBudget, DenialAuditTelemetry denialTelemetry) {
        this.store = store;
        this.databaseUnavailability = databaseUnavailability;
        this.denialBudget = denialBudget;
        this.denialTelemetry = denialTelemetry;
        this.contexts = contexts;
        this.providerContexts = providerContexts;
        this.signer = signer;
        this.execution = execution;
        this.clock = clock;
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setTimeout(10);
        mandatory = new TransactionTemplate(transactions);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
    }

    @Override
    public void authorizationDenied(TenantPermission permission, AuditDenialReason reason) {
        authorizationDenied(permission, reason, null);
    }

    /**
     * Writes the denial row while its key and identity have budget left (spec 0020, ADR 0032); past that the denial
     * is counted, signalled and left for the sweep's summary row. The caller's refusal never depends on which.
     */
    @Override
    public void authorizationDenied(TenantPermission permission, AuditDenialReason reason, IdentityId identityId) {
        TenantContext context = contexts.current().orElse(null);
        AuditDenialReason effectiveReason = context == null ? AuditDenialReason.NO_TENANT_CONTEXT : reason;
        // Built before the budget is asked, so a missing correlation scope or an invalid event fails as it always did.
        AuditEvent event = event(context, AuditEventType.AUTHORIZATION_DENIED, null, AuditOutcome.DENIED,
                new AuditMetadata.AuthorizationDenied(permission, effectiveReason));
        DenialAuditBudget.Decision decision;
        try {
            decision = denialBudget.decide(context, identityId, effectiveReason, permission, event.correlationId());
        } catch (RuntimeException exception) {
            // A broken budget must never cost a denial its row. The message could carry caller text: class only.
            log.error("Denial audit budget failed; the denial row is written: exceptionClass={}",
                    exception.getClass().getName());
            append(context, event, independent);
            return;
        }
        if (!decision.suppressed()) {
            append(context, event, independent);
            return;
        }
        denialTelemetry.suppressed(effectiveReason, decision.cause());
        if (!decision.first()) {
            return;
        }
        if (decision.cause() == DenialAuditBudget.Cause.TRACKER_FULL) {
            log.error("Denial audit tracker is full; denials of untracked keys are counted without rows");
        } else {
            log.warn("Authorization denial rows suppressed for this window: identity={}, reason={}, permission={}, "
                    + "cause={}", decision.identity() == null ? "unknown" : decision.identity().value(),
                    effectiveReason, permission, decision.cause());
        }
    }

    /**
     * Appends one count of suppressed denials under its fixed row id, in its own transaction. Attributed counts are
     * written under the tenant context stored with the window and a capability minted now; the database checks the
     * signature and the verified tenant, not whether that membership is still active.
     */
    SummaryWrite writeSuppressionSummary(DenialAuditBudget.Summary summary) {
        TenantContext context = summary.context();
        try {
            if (context == null) {
                execution.runWithCorrelation(summary.correlationId(), () -> appendSummary(null, summary));
            } else {
                contexts.runWithContext(context, () -> execution.runWithCorrelation(summary.correlationId(),
                        () -> appendSummary(context, summary)));
            }
            return SummaryWrite.WRITTEN;
        } catch (DuplicateKeyException exception) {
            // The row id is fixed per count, so a conflict means an earlier attempt committed.
            return SummaryWrite.ALREADY_WRITTEN;
        } catch (RuntimeException exception) {
            boolean retry = databaseUnavailability.isCause(exception)
                    || exception instanceof TransientDataAccessException
                    || exception instanceof TransactionException;
            // SQL/driver exceptions can include row values and statements. Never log their messages or causes.
            log.error("Denial suppression summary was not written: retry={}, exceptionClass={}", retry,
                    exception.getClass().getName());
            return retry ? SummaryWrite.RETRY : SummaryWrite.REJECTED;
        }
    }

    private void appendSummary(TenantContext context, DenialAuditBudget.Summary summary) {
        AuditEvent event = new AuditEvent(summary.summaryId(), clock.instant(),
                context == null ? null : context.tenantId(), context == null ? null : context.identityId(),
                context == null ? null : context.membershipId(), AuditEventType.AUTHORIZATION_DENIALS_SUPPRESSED,
                null, AuditOutcome.DENIED, execution.requireCurrent(),
                new AuditMetadata.AuthorizationDenialsSuppressed(summary.permission(), summary.reason(),
                        summary.suppressedCount()));
        SignedDatabaseContext capability = context == null
                ? null
                : signer.issueAuditContext(context.tenantId(), context.identityId(), context.membershipId());
        independent.executeWithoutResult(status -> store.append(event, capability));
    }

    @Override
    public void membershipRoleChanged(TenantMembershipId target, TenantRole previousRole, TenantRole newRole) {
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, AuditEventType.MEMBERSHIP_ROLE_CHANGED,
                new AuditTarget(AuditTarget.Type.MEMBERSHIP, target.value()), AuditOutcome.SUCCESS,
                new AuditMetadata.MembershipRoleChanged(previousRole, newRole)), mandatory);
    }

    @Override
    public void membershipCreated(TenantMembershipId target, TenantRole role) {
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, AuditEventType.MEMBERSHIP_CREATED,
                new AuditTarget(AuditTarget.Type.MEMBERSHIP, target.value()), AuditOutcome.SUCCESS,
                new AuditMetadata.MembershipCreated(role)), mandatory);
    }

    @Override
    public void membershipRevoked(TenantMembershipId target) {
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, AuditEventType.MEMBERSHIP_REVOKED,
                new AuditTarget(AuditTarget.Type.MEMBERSHIP, target.value()), AuditOutcome.SUCCESS,
                new AuditMetadata.MembershipRevoked()), mandatory);
    }

    @Override
    public void membershipStatusChanged(TenantMembershipId target, AuditEventType eventType, TenantRole role) {
        if (eventType != AuditEventType.MEMBERSHIP_INVITATION_ACCEPTED
                && eventType != AuditEventType.MEMBERSHIP_REACTIVATED) {
            throw new IllegalArgumentException("Unsupported membership status audit event");
        }
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, eventType,
                new AuditTarget(AuditTarget.Type.MEMBERSHIP, target.value()), AuditOutcome.SUCCESS,
                new AuditMetadata.MembershipStatusChanged(role)), mandatory);
    }

    @Override
    public void customerMutated(UUID target, AuditEventType eventType) {
        if (eventType != AuditEventType.CUSTOMER_CREATED && eventType != AuditEventType.CUSTOMER_UPDATED
                && eventType != AuditEventType.CUSTOMER_ARCHIVED
                && eventType != AuditEventType.CUSTOMER_CONSENT_CHANGED
                && eventType != AuditEventType.CUSTOMER_DO_NOT_CONTACT_CHANGED) {
            throw new IllegalArgumentException("Unsupported customer audit event");
        }
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, eventType, new AuditTarget(AuditTarget.Type.CUSTOMER, target),
                AuditOutcome.SUCCESS, new AuditMetadata.CustomerMutation()), mandatory);
    }

    @Override
    public void integrationUpdated(UUID integrationId, boolean enabled) {
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, AuditEventType.INTEGRATION_UPDATED,
                new AuditTarget(AuditTarget.Type.INTEGRATION, integrationId), AuditOutcome.SUCCESS,
                new AuditMetadata.IntegrationToggle(enabled)), mandatory);
    }

    @Override
    public void outboundKillSwitchChanged(UUID integrationId, boolean enabled) {
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, AuditEventType.OUTBOUND_KILL_SWITCH_CHANGED,
                new AuditTarget(AuditTarget.Type.INTEGRATION, integrationId), AuditOutcome.SUCCESS,
                new AuditMetadata.IntegrationToggle(enabled)), mandatory);
    }

    @Override
    public void templateMappingUpdated(UUID mappingId, boolean enabled) {
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, AuditEventType.TEMPLATE_MAPPING_UPDATED,
                new AuditTarget(AuditTarget.Type.TEMPLATE_MAPPING, mappingId), AuditOutcome.SUCCESS,
                new AuditMetadata.IntegrationToggle(enabled)), mandatory);
    }

    @Override
    public void purchaseMutated(UUID target, AuditEventType eventType) {
        if (eventType != AuditEventType.PURCHASE_RECORDED && eventType != AuditEventType.PURCHASE_CORRECTED
                && eventType != AuditEventType.PURCHASE_VOIDED) {
            throw new IllegalArgumentException("Unsupported purchase audit event");
        }
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, eventType, new AuditTarget(AuditTarget.Type.PURCHASE, target),
                AuditOutcome.SUCCESS, new AuditMetadata.PurchaseMutation()), mandatory);
    }

    @Override
    public void followUpMutated(AuditTarget.Type targetType, UUID target, AuditEventType eventType) {
        boolean tenantEvent = eventType == AuditEventType.TENANT_FOLLOW_UP_POLICY_CHANGED;
        boolean customerEvent = eventType == AuditEventType.CUSTOMER_FOLLOW_UP_POLICY_CHANGED
                || eventType == AuditEventType.FOLLOW_UP_SNOOZED
                || eventType == AuditEventType.MANUAL_FOLLOW_UP_RECORDED
                || eventType == AuditEventType.FOLLOW_UP_DISMISSED;
        if ((tenantEvent && targetType != AuditTarget.Type.TENANT)
                || (customerEvent && targetType != AuditTarget.Type.CUSTOMER)
                || (!tenantEvent && !customerEvent)) {
            throw new IllegalArgumentException("Unsupported follow-up audit event");
        }
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, eventType, new AuditTarget(targetType, target), AuditOutcome.SUCCESS,
                new AuditMetadata.FollowUpMutation()), mandatory);
    }

    @Override
    public void aiInvocationOutcome(UUID customerId, AiAuditOperation operation, AiAuditOutcome outcome,
            AiAuditDetail detail) {
        TenantContext context = contexts.requireCurrent();
        AuditOutcome auditOutcome = switch (outcome) {
            case GENERATED, MODEL_REFUSED -> AuditOutcome.SUCCESS;
            case GATE_REJECTED -> AuditOutcome.DENIED;
            case FAILED -> AuditOutcome.FAILURE;
        };
        append(context, event(context, AuditEventType.AI_INVOCATION_OUTCOME,
                new AuditTarget(AuditTarget.Type.CUSTOMER, customerId), auditOutcome,
                new AuditMetadata.AiInvocation(operation, outcome, detail)), independent);
    }

    @Override
    public void messageTransition(UUID messageId, AuditEventType eventType,
            AuditMetadata.MessageTransition metadata) {
        if (!eventType.isMessageTransition() || eventType == AuditEventType.MESSAGE_DELIVERY_UPDATED) {
            throw new IllegalArgumentException("Unsupported message audit event");
        }
        TenantContext context = contexts.requireCurrent();
        append(context, event(context, eventType, new AuditTarget(AuditTarget.Type.MESSAGE, messageId),
                AuditOutcome.SUCCESS, metadata), mandatory);
    }

    @Override
    public void messageDeliveryUpdated(TenantId tenantId, UUID messageId,
            AuditMetadata.MessageTransition metadata) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        if (contexts.current().isPresent()) {
            throw new IllegalStateException("Provider attributed audit cannot run under a member context");
        }
        // The caller's tenant is not trusted on its own: it must be the tenant the webhook boundary bound.
        if (!providerContexts.requireCurrent().tenantId().equals(tenantId)) {
            throw new IllegalStateException("Provider attributed audit tenant does not match the provider context");
        }
        AuditEvent event = new AuditEvent(UUID.randomUUID(), clock.instant(), tenantId, null, null,
                AuditEventType.MESSAGE_DELIVERY_UPDATED, new AuditTarget(AuditTarget.Type.MESSAGE, messageId),
                AuditOutcome.SUCCESS, execution.requireCurrent(), metadata);
        SignedDatabaseContext capability = signer.issueProviderAuditContext(tenantId);
        try {
            mandatory.executeWithoutResult(status -> store.append(event, capability));
        } catch (RuntimeException exception) {
            log.error("Audit persistence failed; correlationId={}", event.correlationId());
            throw new AuditPersistenceException(databaseUnavailability.isCause(exception));
        }
    }

    private AuditEvent event(TenantContext context, AuditEventType type, AuditTarget target,
            AuditOutcome outcome, AuditMetadata metadata) {
        return new AuditEvent(UUID.randomUUID(), clock.instant(), context == null ? null : context.tenantId(),
                context == null ? null : context.identityId(), context == null ? null : context.membershipId(),
                type, target, outcome, execution.requireCurrent(), metadata);
    }

    private void append(TenantContext context, AuditEvent event, TransactionTemplate transaction) {
        SignedDatabaseContext capability = context == null
                ? null
                : signer.issueAuditContext(context.tenantId(), context.identityId(), context.membershipId());
        try {
            transaction.executeWithoutResult(status -> store.append(event, capability));
        } catch (RuntimeException exception) {
            // SQL/driver exceptions can include row values and statements. Never log their messages or causes.
            log.error("Audit persistence failed; correlationId={}", event.correlationId());
            throw new AuditPersistenceException(databaseUnavailability.isCause(exception));
        }
    }
}
