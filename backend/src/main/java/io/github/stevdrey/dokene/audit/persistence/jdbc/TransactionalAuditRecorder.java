package io.github.stevdrey.dokene.audit.persistence.jdbc;

import io.github.stevdrey.dokene.audit.application.AuditExecutionContext;
import io.github.stevdrey.dokene.audit.application.AuditPersistenceException;
import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.audit.domain.AuditEvent;
import io.github.stevdrey.dokene.audit.domain.AuditEventType;
import io.github.stevdrey.dokene.audit.domain.AuditMetadata;
import io.github.stevdrey.dokene.audit.domain.AuditOutcome;
import io.github.stevdrey.dokene.audit.domain.AuditTarget;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class TransactionalAuditRecorder implements AuditRecorder {
    private static final Logger log = LoggerFactory.getLogger(TransactionalAuditRecorder.class);
    private final JdbcAuditStore store;
    private final TenantContextProvider contexts;
    private final AuditExecutionContext execution;
    private final Clock clock;
    private final TransactionTemplate independent;
    private final TransactionTemplate mandatory;

    TransactionalAuditRecorder(JdbcAuditStore store, TenantContextProvider contexts, AuditExecutionContext execution,
            Clock clock, PlatformTransactionManager transactions) {
        this.store = store;
        this.contexts = contexts;
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
        TenantContext context = contexts.current().orElse(null);
        append(event(context, AuditEventType.AUTHORIZATION_DENIED, null, AuditOutcome.DENIED,
                new AuditMetadata.AuthorizationDenied(permission, context == null ? AuditDenialReason.NO_TENANT_CONTEXT : reason)),
                independent);
    }

    @Override
    public void membershipRoleChanged(TenantMembershipId target, TenantRole previousRole, TenantRole newRole) {
        append(event(contexts.requireCurrent(), AuditEventType.MEMBERSHIP_ROLE_CHANGED,
                new AuditTarget(AuditTarget.Type.MEMBERSHIP, target.value()), AuditOutcome.SUCCESS,
                new AuditMetadata.MembershipRoleChanged(previousRole, newRole)), mandatory);
    }

    private AuditEvent event(TenantContext context, AuditEventType type, AuditTarget target,
            AuditOutcome outcome, AuditMetadata metadata) {
        return new AuditEvent(UUID.randomUUID(), clock.instant(), context == null ? null : context.tenantId(),
                context == null ? null : context.identityId(), context == null ? null : context.membershipId(),
                type, target, outcome, execution.requireCurrent(), metadata);
    }

    private void append(AuditEvent event, TransactionTemplate transaction) {
        try {
            transaction.executeWithoutResult(status -> store.append(event));
        } catch (RuntimeException exception) {
            // SQL/driver exceptions can include row values and statements. Never log their messages or causes.
            log.error("Audit persistence failed; correlationId={}", event.correlationId());
            throw new AuditPersistenceException();
        }
    }
}
