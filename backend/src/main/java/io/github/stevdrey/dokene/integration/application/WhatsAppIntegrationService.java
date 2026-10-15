package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.application.MappingRejectionLimiter.MappingAttempt;
import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant and actor come from the current context, never from the request. Credentials are only referenced: the
 * reference's format is validated here, but the secret is resolved fail-closed by the provider adapter at send time
 * (ADR 0023 §3.3, ADR 0024 §3), never when configuration is written. No network call is made.
 */
@Service
public class WhatsAppIntegrationService {
    private final WhatsAppIntegrationRepository repository;
    private final IntegrationAuditPort audit;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final IntegrationReadiness readiness;
    private final MappingRejectionLimiter rejections;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public WhatsAppIntegrationService(WhatsAppIntegrationRepository repository, IntegrationAuditPort audit,
            TenantAuthorizationService authorization, TenantContextProvider contexts, IntegrationReadiness readiness,
            MappingRejectionLimiter rejections, Clock clock, PlatformTransactionManager transactions) {
        this.repository = Objects.requireNonNull(repository, "Repository is required");
        this.audit = Objects.requireNonNull(audit, "Audit port is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.readiness = Objects.requireNonNull(readiness, "Readiness is required");
        this.rejections = Objects.requireNonNull(rejections, "Rejection limiter is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        Objects.requireNonNull(transactions, "Transaction manager is required");
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactions);
    }

    /** The stored configuration plus the runtime state reported with it. */
    public record IntegrationView(WhatsAppIntegration integration, boolean outboundEnabled, boolean webhookVerified) {
    }

    @Transactional(readOnly = true)
    public IntegrationView get() {
        authorization.requirePermission(TenantPermission.INTEGRATION_READ);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        return view(repository.findByTenant(tenantId)
                .orElseThrow(() -> new IntegrationRefusedException(MessagingErrorCode.NOT_FOUND)));
    }

    /** The caller's explicit precondition: create only when absent, or replace exactly this version. */
    public sealed interface Precondition permits Create, Version {
    }

    /** {@code If-None-Match: *}: succeeds only while the tenant has no integration. */
    public record Create() implements Precondition {
    }

    /** {@code If-Match: "<version>"}: succeeds only while the stored version equals this one. */
    public record Version(long value) implements Precondition {
    }

    /**
     * A missing precondition cannot reach here, so nothing is ever created or replaced unconditionally. A write that
     * changes the mapping is refused with {@code RATE_LIMITED} while the tenant or actor has exhausted its budget of
     * phone numbers already mapped by another tenant (issue 208); keeping the stored number is never throttled.
     *
     * <p>Not one transaction: the budget check, its audit and the rejection bookkeeping run between a short read and
     * the write transaction, so a refused or rejected attempt never holds a connection while its denial is audited.
     */
    public IntegrationView upsert(Precondition precondition, String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, boolean enabled, String secretRef) {
        authorization.requirePermission(TenantPermission.INTEGRATION_MANAGE);
        TenantContext context = contexts.requireCurrent();
        boolean changesMapping = Boolean.TRUE.equals(reads.execute(status -> changesMapping(
                repository.findByTenant(context.tenantId()), phoneNumberId)));
        // A null attempt (the stored number is kept) is allowed by try-with-resources and never closed.
        try (MappingAttempt attempt = changesMapping ? beginMappingAttempt(context) : null) {
            try {
                return writes.execute(status -> write(context.tenantId(), changesMapping, precondition,
                        phoneNumberId, businessAccountId, displayPhoneNumber, enabled, secretRef));
            } catch (PhoneNumberMappingConflictException conflict) {
                // The write transaction has rolled back, so the audit below runs on its own connection only.
                if (attempt != null && attempt.reject()) {
                    audit.mappingThrottled();
                }
                throw conflict;
            }
        }
    }

    private IntegrationView write(TenantId tenantId, boolean reserved, Precondition precondition,
            String phoneNumberId, String businessAccountId, String displayPhoneNumber, boolean enabled,
            String secretRef) {
        Optional<WhatsAppIntegration> existing = repository.findByTenant(tenantId);
        if (!reserved && changesMapping(existing, phoneNumberId)) {
            // The stored number moved after the unreserved pre-read; this write would be an uncounted mapping change.
            throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
        }
        WhatsAppIntegration saved;
        if (existing.isEmpty()) {
            if (!(precondition instanceof Create)) {
                throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
            }
            WhatsAppIntegration created = validated(() -> WhatsAppIntegration.create(tenantId, phoneNumberId,
                    businessAccountId, displayPhoneNumber, enabled, secretRef, clock.instant()));
            repository.insert(created);
            saved = created;
        } else {
            WhatsAppIntegration current = existing.get();
            if (!(precondition instanceof Version(long expectedVersion))
                    || current.version() != expectedVersion) {
                throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
            }
            WhatsAppIntegration next = validated(() -> current.update(phoneNumberId, businessAccountId,
                    displayPhoneNumber, enabled, secretRef, clock.instant()));
            long storedVersion = repository.update(next, expectedVersion).orElseThrow(
                    () -> new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION));
            saved = next.withVersion(storedVersion);
        }
        audit.updated(saved.id(), saved.enabled());
        return view(saved);
    }

    private static boolean changesMapping(Optional<WhatsAppIntegration> existing, String phoneNumberId) {
        return existing.map(current -> !current.phoneNumberId().equals(phoneNumberId)).orElse(true);
    }

    /**
     * Controlled rotation (ADR 0024 addendum): the operator replaced the secret in the store behind the same
     * reference and calls this to bump the credential revision. Never touches {@code enabled}.
     */
    @Transactional
    public IntegrationView rotateCredential(long expectedVersion) {
        authorization.requirePermission(TenantPermission.INTEGRATION_MANAGE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        WhatsAppIntegration current = repository.findByTenant(tenantId)
                .orElseThrow(() -> new IntegrationRefusedException(MessagingErrorCode.NOT_FOUND));
        if (current.version() != expectedVersion) {
            throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
        }
        WhatsAppIntegration next = current.rotateCredential(clock.instant());
        long storedVersion = repository.update(next, expectedVersion).orElseThrow(
                () -> new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION));
        WhatsAppIntegration saved = next.withVersion(storedVersion);
        audit.updated(saved.id(), saved.enabled());
        return view(saved);
    }

    /**
     * Reserves budget before the write, so concurrent attempts cannot reveal more conflicts than allowed. Every
     * throttled attempt is a denied action, audited in its own transaction before the refusal propagates.
     */
    private MappingAttempt beginMappingAttempt(TenantContext context) {
        try {
            return rejections.begin(context.tenantId(), context.identityId());
        } catch (IntegrationRateLimitedException throttled) {
            audit.mappingThrottled();
            throw throttled;
        }
    }

    /** Only domain validation maps to INVALID_INPUT; other failures stay server errors. */
    private static WhatsAppIntegration validated(Supplier<WhatsAppIntegration> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationRefusedException(MessagingErrorCode.INVALID_INPUT);
        }
    }

    private IntegrationView view(WhatsAppIntegration integration) {
        return new IntegrationView(integration, readiness.outboundEnabled(integration.tenantId()),
                readiness.webhookVerified(integration.tenantId()));
    }
}
