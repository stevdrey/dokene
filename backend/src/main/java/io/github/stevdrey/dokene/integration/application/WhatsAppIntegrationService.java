package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.application.MappingRejectionLimiter.MappingAttempt;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Claim;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.NotProven;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Outcome;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Proven;
import io.github.stevdrey.dokene.integration.application.PhoneNumberOwnershipVerifier.Unavailable;
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
 * reference's format is validated here, but the secret is resolved fail-closed by the provider adapter (ADR 0023 §3.3,
 * ADR 0024 §3), never when configuration is written. Writing configuration makes no network call; only the explicit
 * ownership verification asks the adapter to prove the mapping (issue 210).
 */
@Service
public class WhatsAppIntegrationService {
    private final WhatsAppIntegrationRepository repository;
    private final IntegrationAuditPort audit;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final IntegrationReadiness readiness;
    private final PhoneNumberOwnershipVerifier ownership;
    private final MappingRejectionLimiter rejections;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public WhatsAppIntegrationService(WhatsAppIntegrationRepository repository, IntegrationAuditPort audit,
            TenantAuthorizationService authorization, TenantContextProvider contexts, IntegrationReadiness readiness,
            PhoneNumberOwnershipVerifier ownership, MappingRejectionLimiter rejections, Clock clock,
            PlatformTransactionManager transactions) {
        this.repository = Objects.requireNonNull(repository, "Repository is required");
        this.audit = Objects.requireNonNull(audit, "Audit port is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.readiness = Objects.requireNonNull(readiness, "Readiness is required");
        this.ownership = Objects.requireNonNull(ownership, "Ownership verifier is required");
        this.rejections = Objects.requireNonNull(rejections, "Rejection limiter is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        Objects.requireNonNull(transactions, "Transaction manager is required");
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
        this.writes = new TransactionTemplate(transactions);
    }

    /**
     * The stored configuration plus the state reported with it. {@code webhookVerified} is the persisted ownership
     * proof for the current phone number, business account and credential revision.
     */
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
     * A missing precondition cannot reach here, so nothing is ever created or replaced unconditionally. Any well-formed
     * phone number id is accepted: the stored mapping stays unverified, and unverified mappings of one number may
     * coexist across tenants, so naming another tenant's number neither blocks it nor reveals anything (issue 210).
     */
    @Transactional
    public IntegrationView upsert(Precondition precondition, String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, boolean enabled, String secretRef) {
        authorization.requirePermission(TenantPermission.INTEGRATION_MANAGE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        Optional<WhatsAppIntegration> existing = repository.findByTenant(tenantId);
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

    /**
     * Asks the provider adapter to prove, with the tenant's own credential, that the stored phone number belongs to the
     * stored business account, and records the proof against exactly that configuration version. The real owner can
     * always verify, even when another tenant mapped the number first; only one tenant can hold a verified mapping.
     *
     * <p>Only an enabled integration can be verified; a disabled one is {@code INVALID_INPUT} without a provider call.
     *
     * <p>A number the credential does not reach and a number another tenant already verified are answered alike with
     * {@code INVALID_INPUT} and count against the rejection budget (issue 208), so the outcome is not an oracle for
     * other tenants' mappings. An inconclusive provider answer is {@code PROVIDER_UNAVAILABLE} and costs no budget.
     *
     * <p>Not one transaction: the provider call runs between a short read and a short compare-and-set write, so no
     * connection is held across the network, and a configuration change in between is a {@code STALE_VERSION}.
     */
    public IntegrationView verifyOwnership(long expectedVersion) {
        authorization.requirePermission(TenantPermission.INTEGRATION_MANAGE);
        TenantContext context = contexts.requireCurrent();
        WhatsAppIntegration current = reads.execute(status -> repository.findByTenant(context.tenantId()))
                .orElseThrow(() -> new IntegrationRefusedException(MessagingErrorCode.NOT_FOUND));
        if (current.version() != expectedVersion) {
            throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
        }
        if (!current.enabled()) {
            // Deterministic and reveals nothing about other tenants, so it neither calls the provider nor costs budget.
            throw new IntegrationRefusedException(MessagingErrorCode.INVALID_INPUT);
        }
        try (MappingAttempt attempt = beginMappingAttempt(context)) {
            Outcome outcome = ownership.verify(new Claim(current.tenantId(), current.phoneNumberId(),
                    current.businessAccountId(), current.secretRef()));
            switch (outcome) {
                case Unavailable _ ->
                        throw new IntegrationRefusedException(MessagingErrorCode.PROVIDER_UNAVAILABLE);
                case NotProven _ -> throw rejected(attempt, new PhoneNumberMappingConflictException());
                case Proven _ -> {
                    try {
                        return writes.execute(status -> recordProof(current, expectedVersion));
                    } catch (PhoneNumberMappingConflictException conflict) {
                        // The write transaction has rolled back, so the audit below runs on its own connection only.
                        throw rejected(attempt, conflict);
                    }
                }
            }
        }
    }

    private IntegrationView recordProof(WhatsAppIntegration current, long expectedVersion) {
        WhatsAppIntegration next = current.verifyOwnership(clock.instant());
        long storedVersion = repository.update(next, expectedVersion).orElseThrow(
                () -> new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION));
        WhatsAppIntegration saved = next.withVersion(storedVersion);
        audit.updated(saved.id(), saved.enabled());
        return view(saved);
    }

    /** Counts the attempt and audits the denial before the refusal propagates; an audit failure still counts. */
    private PhoneNumberMappingConflictException rejected(MappingAttempt attempt,
            PhoneNumberMappingConflictException refusal) {
        attempt.reject();
        audit.mappingRejected();
        return refusal;
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
     * Reserves budget before the provider call, so concurrent attempts cannot reveal more rejections than allowed. Every
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
                integration.ownershipVerified());
    }
}
