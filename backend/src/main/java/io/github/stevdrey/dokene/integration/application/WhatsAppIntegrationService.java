package io.github.stevdrey.dokene.integration.application;

import io.github.stevdrey.dokene.integration.domain.WhatsAppIntegration;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final Clock clock;

    public WhatsAppIntegrationService(WhatsAppIntegrationRepository repository, IntegrationAuditPort audit,
            TenantAuthorizationService authorization, TenantContextProvider contexts, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Repository is required");
        this.audit = Objects.requireNonNull(audit, "Audit port is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional(readOnly = true)
    public WhatsAppIntegration get() {
        authorization.requirePermission(TenantPermission.INTEGRATION_READ);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        return repository.findByTenant(tenantId)
                .orElseThrow(() -> new IntegrationRefusedException(MessagingErrorCode.NOT_FOUND));
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

    /** A missing precondition cannot reach here, so nothing is ever created or replaced unconditionally. */
    @Transactional
    public WhatsAppIntegration upsert(Precondition precondition, String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, boolean enabled, String secretRef) {
        authorization.requirePermission(TenantPermission.INTEGRATION_MANAGE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        Optional<WhatsAppIntegration> existing = repository.findByTenant(tenantId);
        WhatsAppIntegration saved;
        try {
            if (existing.isEmpty()) {
                if (!(precondition instanceof Create)) {
                    throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
                }
                WhatsAppIntegration created = WhatsAppIntegration.create(tenantId, phoneNumberId, businessAccountId,
                        displayPhoneNumber, enabled, secretRef, clock.instant());
                repository.insert(created);
                saved = created;
            } else {
                WhatsAppIntegration current = existing.get();
                if (!(precondition instanceof Version(long expectedVersion))) {
                    throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
                }
                if (current.version() != expectedVersion) {
                    throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
                }
                WhatsAppIntegration next = current.update(phoneNumberId, businessAccountId, displayPhoneNumber,
                        enabled, secretRef, clock.instant());
                if (!repository.update(next, expectedVersion)) {
                    throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
                }
                saved = new WhatsAppIntegration(next.id(), next.tenantId(), next.phoneNumberId(),
                        next.businessAccountId(), next.displayPhoneNumber(), next.enabled(), next.secretRef(),
                        next.credentialRevision(), next.createdAt(), next.updatedAt(), expectedVersion + 1);
            }
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationRefusedException(MessagingErrorCode.INVALID_INPUT);
        }
        audit.updated(saved.id(), saved.enabled());
        return saved;
    }
}
