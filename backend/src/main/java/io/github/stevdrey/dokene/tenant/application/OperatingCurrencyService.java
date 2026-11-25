// ABOUTME: Reads and changes the current tenant's operating currency (issue 452), the setting the message safety rules
// ABOUTME: read to rule out amounts in a currency the tenant does not quote. A change is version-checked and audited.
package io.github.stevdrey.dokene.tenant.application;

import io.github.stevdrey.dokene.audit.application.AuditRecorder;
import io.github.stevdrey.dokene.tenant.domain.Tenant;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import java.time.Clock;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperatingCurrencyService {

    private final TenantRepository tenants;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final AuditRecorder audit;
    private final Clock clock;

    public OperatingCurrencyService(TenantRepository tenants, TenantAuthorizationService authorization,
            TenantContextProvider contexts, AuditRecorder audit, Clock clock) {
        this.tenants = Objects.requireNonNull(tenants, "Tenant repository is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization service is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.audit = Objects.requireNonNull(audit, "Audit recorder is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    /** The setting with the tenant revision it was read at, which a change must name. */
    public record Setting(Optional<Currency> operatingCurrency, long revision) {
        public Setting {
            Objects.requireNonNull(operatingCurrency, "Operating currency is required");
        }
    }

    /** The tenant changed since the revision the caller read. */
    public static final class StaleRevisionException extends RuntimeException {
        public StaleRevisionException() {
            super("Tenant revision is stale");
        }
    }

    @Transactional(readOnly = true)
    public Setting read() {
        authorization.requirePermission(TenantPermission.TENANT_READ);
        return setting(currentTenant());
    }

    /**
     * Sets the currency, or clears it with {@code null}, when the tenant is still at {@code expectedRevision}. An
     * unchanged value writes nothing and records no audit row. A change is audited in the same transaction, so a
     * failed audit rolls it back.
     */
    @Transactional
    public Setting change(Currency currency, long expectedRevision) {
        authorization.requirePermission(TenantPermission.TENANT_UPDATE);
        Tenant tenant = currentTenant();
        if (tenant.revision().orElseThrow() != expectedRevision) {
            throw new StaleRevisionException();
        }
        if (tenant.operatingCurrency().equals(Optional.ofNullable(currency))) {
            return setting(tenant);
        }
        tenant.changeOperatingCurrency(currency, clock.instant());
        try {
            tenants.save(tenant);
        } catch (OptimisticLockingFailureException concurrentChange) {
            throw new StaleRevisionException();
        }
        audit.tenantOperatingCurrencyChanged();
        return setting(tenant);
    }

    private Tenant currentTenant() {
        return tenants.findById(contexts.requireCurrent().tenantId())
                .orElseThrow(() -> new TenantAccessDeniedException("Tenant is not available"));
    }

    private static Setting setting(Tenant tenant) {
        return new Setting(tenant.operatingCurrency(), tenant.revision().orElseThrow());
    }
}
