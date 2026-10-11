// ABOUTME: Read, replace and kill switch for the tenant integration row (spec 0005 AC-2 to AC-5).
// ABOUTME: Shape validation before the transaction, then lock, version compare, semantic refusals, write, audit.
package io.github.stevdrey.dokene.integration.config;

import io.github.stevdrey.dokene.customer.application.PhoneNormalizer;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IntegrationConfigurationService {
    private static final Logger log = LoggerFactory.getLogger(IntegrationConfigurationService.class);
    private static final Pattern IDENTIFIER = Pattern.compile("[0-9]{1,32}");

    private final TenantMessagingIntegrationRepository repository;
    private final IntegrationSecretResolver secrets;
    private final PhoneNormalizer phones;
    private final IntegrationAuditPort audit;
    private final TenantContextProvider contexts;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public IntegrationConfigurationService(TenantMessagingIntegrationRepository repository,
            IntegrationSecretResolver secrets, PhoneNormalizer phones, IntegrationAuditPort audit,
            TenantContextProvider contexts, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "Repository is required");
        this.secrets = Objects.requireNonNull(secrets, "Secret resolver is required");
        this.phones = Objects.requireNonNull(phones, "Phone normalizer is required");
        this.audit = Objects.requireNonNull(audit, "Audit port is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** The row of the current tenant, or the default representation (version 0) when there is none. */
    public TenantMessagingIntegration read() {
        UUID tenantId = currentTenantId();
        return repository.findByTenant(tenantId).orElseGet(() -> TenantMessagingIntegration.absent(tenantId));
    }

    /** Full replace of the configurable fields; outbound_enabled and webhook_verified are preserved. */
    public TenantMessagingIntegration update(IntegrationConfigurationUpdate command) {
        Objects.requireNonNull(command, "Command is required");
        Validated input = validate(command);
        UUID tenantId = currentTenantId();
        return transactions.execute(status -> {
            TenantMessagingIntegration current = repository.lockByTenant(tenantId)
                    .orElseGet(() -> TenantMessagingIntegration.absent(tenantId));
            if (current.version() != command.expectedVersion()) {
                throw new IntegrationRefusedException(IntegrationErrorCode.STALE_VERSION, current.version());
            }
            if (input.enabled() && (input.phoneNumberId() == null || input.businessAccountId() == null
                    || input.displayPhoneNumber() == null || input.secretRef() == null)) {
                throw new IntegrationRefusedException(IntegrationErrorCode.INTEGRATION_INCOMPLETE);
            }
            if (input.secretRef() != null && secrets.resolve(input.secretRef()).isEmpty()) {
                throw new IntegrationRefusedException(IntegrationErrorCode.SECRET_REF_UNRESOLVABLE);
            }
            Set<IntegrationField> changed = changedFields(current, input);
            Instant now = clock.instant();
            TenantMessagingIntegration next = new TenantMessagingIntegration(tenantId, input.phoneNumberId(),
                    input.businessAccountId(), input.displayPhoneNumber(), input.secretRef(), input.enabled(),
                    current.outboundEnabled(), current.webhookVerified(), current.version() + 1,
                    current.stored() ? current.createdAt() : now, now);
            TenantMessagingIntegration saved = current.stored() ? repository.update(current, next)
                    : repository.insert(next);
            audit.integrationUpdated(tenantId, saved.enabled(), changed);
            log.info("Integration updated; tenantId={} operation=update changedFields={}", tenantId, changed);
            return saved;
        });
    }

    /**
     * The only writer of outbound_enabled. The version compare runs first; a value equal to the stored one is a no op
     * that bumps nothing and audits nothing.
     */
    public TenantMessagingIntegration setOutbound(boolean enabled, long expectedVersion) {
        UUID tenantId = currentTenantId();
        return transactions.execute(status -> {
            TenantMessagingIntegration current = repository.lockByTenant(tenantId)
                    .orElseGet(() -> TenantMessagingIntegration.absent(tenantId));
            if (current.version() != expectedVersion) {
                throw new IntegrationRefusedException(IntegrationErrorCode.STALE_VERSION, current.version());
            }
            if (current.outboundEnabled() == enabled) {
                return current;
            }
            Instant now = clock.instant();
            TenantMessagingIntegration next = new TenantMessagingIntegration(tenantId, current.phoneNumberId(),
                    current.businessAccountId(), current.displayPhoneNumber(), current.secretRef(), current.enabled(),
                    enabled, current.webhookVerified(), current.version() + 1,
                    current.stored() ? current.createdAt() : now, now);
            TenantMessagingIntegration saved = current.stored() ? repository.update(current, next)
                    : repository.insert(next);
            audit.outboundSwitchChanged(tenantId, saved.outboundEnabled());
            log.info("Integration kill switch changed; tenantId={} operation=kill-switch enabled={}", tenantId,
                    saved.outboundEnabled());
            return saved;
        });
    }

    private UUID currentTenantId() {
        return contexts.requireCurrent().tenantId().value();
    }

    /** Shape validation, decided before the transaction: identifiers, the display number, the reference grammar. */
    private Validated validate(IntegrationConfigurationUpdate command) {
        String phoneNumberId = identifier(command.phoneNumberId());
        String businessAccountId = identifier(command.businessAccountId());
        String displayPhoneNumber = null;
        if (command.displayPhoneNumber() != null) {
            if (command.displayPhoneRegion() == null) {
                throw new IntegrationRefusedException(IntegrationErrorCode.INVALID_INPUT);
            }
            try {
                displayPhoneNumber = phones.normalize(command.displayPhoneNumber(), command.displayPhoneRegion());
            } catch (IllegalArgumentException invalid) {
                throw new IntegrationRefusedException(IntegrationErrorCode.INVALID_INPUT);
            }
        } else if (command.displayPhoneRegion() != null) {
            throw new IntegrationRefusedException(IntegrationErrorCode.INVALID_INPUT);
        }
        SecretRef secretRef = null;
        if (command.secretRef() != null) {
            try {
                secretRef = SecretRef.parse(command.secretRef());
            } catch (IllegalArgumentException malformed) {
                throw new IntegrationRefusedException(IntegrationErrorCode.SECRET_REF_UNRESOLVABLE);
            }
        }
        return new Validated(phoneNumberId, businessAccountId, displayPhoneNumber, secretRef, command.enabled());
    }

    private static String identifier(String value) {
        if (value == null) {
            return null;
        }
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IntegrationRefusedException(IntegrationErrorCode.INVALID_INPUT);
        }
        return value;
    }

    static Set<IntegrationField> changedFields(TenantMessagingIntegration current, Validated next) {
        EnumSet<IntegrationField> changed = EnumSet.noneOf(IntegrationField.class);
        if (!Objects.equals(current.phoneNumberId(), next.phoneNumberId())) {
            changed.add(IntegrationField.PHONE_NUMBER_ID);
        }
        if (!Objects.equals(current.businessAccountId(), next.businessAccountId())) {
            changed.add(IntegrationField.BUSINESS_ACCOUNT_ID);
        }
        if (!Objects.equals(current.displayPhoneNumber(), next.displayPhoneNumber())) {
            changed.add(IntegrationField.DISPLAY_PHONE_NUMBER);
        }
        if (!Objects.equals(current.secretRef(), next.secretRef())) {
            changed.add(IntegrationField.SECRET_REF);
        }
        if (current.enabled() != next.enabled()) {
            changed.add(IntegrationField.ENABLED);
        }
        return changed;
    }

    record Validated(String phoneNumberId, String businessAccountId, String displayPhoneNumber, SecretRef secretRef,
            boolean enabled) {
    }
}
