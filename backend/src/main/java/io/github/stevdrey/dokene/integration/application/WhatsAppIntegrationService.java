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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant and actor come from the current context, never from the request. Credentials are only referenced: the secret
 * is resolved to prove the reference is usable and is closed immediately; no network call is made.
 */
@Service
public class WhatsAppIntegrationService {
    private final WhatsAppIntegrationRepository repository;
    private final IntegrationAuditPort audit;
    private final ObjectProvider<IntegrationSecretResolver> secrets;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final Clock clock;

    public WhatsAppIntegrationService(WhatsAppIntegrationRepository repository, IntegrationAuditPort audit,
            ObjectProvider<IntegrationSecretResolver> secrets, TenantAuthorizationService authorization,
            TenantContextProvider contexts, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Repository is required");
        this.audit = Objects.requireNonNull(audit, "Audit port is required");
        this.secrets = Objects.requireNonNull(secrets, "Secret resolver is required");
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

    /**
     * Creates the tenant's integration, or replaces it when {@code expectedVersion} matches. Absent record requires no
     * version; an existing one requires one.
     */
    @Transactional
    public WhatsAppIntegration upsert(Long expectedVersion, String phoneNumberId, String businessAccountId,
            String displayPhoneNumber, boolean enabled, String secretRef) {
        authorization.requirePermission(TenantPermission.INTEGRATION_MANAGE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        Optional<WhatsAppIntegration> existing = repository.findByTenant(tenantId);
        WhatsAppIntegration saved;
        try {
            if (existing.isEmpty()) {
                if (expectedVersion != null) {
                    throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
                }
                WhatsAppIntegration created = WhatsAppIntegration.create(tenantId, phoneNumberId, businessAccountId,
                        displayPhoneNumber, enabled, secretRef, clock.instant());
                requireUsableSecret(tenantId, secretRef);
                repository.insert(created);
                saved = created;
            } else {
                WhatsAppIntegration current = existing.get();
                if (expectedVersion == null) {
                    throw new IntegrationRefusedException(MessagingErrorCode.INVALID_INPUT);
                }
                if (current.version() != expectedVersion) {
                    throw new IntegrationRefusedException(MessagingErrorCode.STALE_VERSION);
                }
                WhatsAppIntegration next = current.update(phoneNumberId, businessAccountId, displayPhoneNumber,
                        enabled, secretRef, clock.instant());
                if (!current.secretRef().equals(next.secretRef()) || (enabled && !current.enabled())) {
                    requireUsableSecret(tenantId, next.secretRef());
                }
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

    /** Unknown, missing, wrong-scope or unconfigured resolution all fail closed with the same refusal. */
    private void requireUsableSecret(TenantId tenantId, String secretRef) {
        IntegrationSecretResolver resolver = secrets.getIfAvailable();
        if (resolver == null) {
            throw new IntegrationRefusedException(MessagingErrorCode.INVALID_INPUT);
        }
        try (var ignored = resolver.resolve(new IntegrationSecretResolver.Tenant(tenantId),
                new IntegrationSecretResolver.SecretReference(secretRef))) {
            // Resolution success is the only evidence needed; the material is never read here.
        } catch (RuntimeException unresolved) {
            throw new IntegrationRefusedException(MessagingErrorCode.INVALID_INPUT);
        }
    }
}
