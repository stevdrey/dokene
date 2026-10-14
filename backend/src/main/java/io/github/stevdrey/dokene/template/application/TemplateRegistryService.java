package io.github.stevdrey.dokene.template.application;

import io.github.stevdrey.dokene.ai.domain.SemanticTemplateIntent;
import io.github.stevdrey.dokene.tenant.application.TenantAuthorizationService;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import io.github.stevdrey.dokene.template.domain.TemplateApprovalStatus;
import io.github.stevdrey.dokene.template.domain.TemplateMapping;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tenant and actor come from the current context, never from the request. Template permissions never grant
 * integration management: the sender identity is only read through the gateway. The provider call of a refresh runs
 * outside any transaction, and only its normalized evidence is stored (ADR 0025).
 */
@Service
public class TemplateRegistryService {
    private final TemplateMappingRepository repository;
    private final TemplateProviderGateway gateway;
    private final TemplateAuditPort audit;
    private final TenantAuthorizationService authorization;
    private final TenantContextProvider contexts;
    private final TemplateRegistryProperties properties;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;

    public TemplateRegistryService(TemplateMappingRepository repository, TemplateProviderGateway gateway,
            TemplateAuditPort audit, TenantAuthorizationService authorization, TenantContextProvider contexts,
            TemplateRegistryProperties properties, Clock clock, PlatformTransactionManager transactions) {
        this.repository = Objects.requireNonNull(repository, "Repository is required");
        this.gateway = Objects.requireNonNull(gateway, "Gateway is required");
        this.audit = Objects.requireNonNull(audit, "Audit port is required");
        this.authorization = Objects.requireNonNull(authorization, "Authorization is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.properties = Objects.requireNonNull(properties, "Properties are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.writes = new TransactionTemplate(transactions);
        this.reads = new TransactionTemplate(transactions);
        this.reads.setReadOnly(true);
    }

    /** One catalogue slot per closed intent; {@code mapping} is empty while the intent is unmapped. */
    public record Entry(SemanticTemplateIntent intent, Optional<TemplateMapping> mapping, boolean sendable) {
    }

    /** The caller's explicit precondition: create only when absent, or replace exactly this version. */
    public sealed interface Precondition permits Create, Version {
    }

    public record Create() implements Precondition {
    }

    public record Version(long value) implements Precondition {
    }

    public List<Entry> list() {
        authorization.requirePermission(TenantPermission.TEMPLATE_READ);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        return reads.execute(status -> {
            List<TemplateMapping> stored = repository.findAll(tenantId);
            Optional<TemplateProviderGateway.Sender> sender = gateway.currentSender();
            List<Entry> entries = new ArrayList<>();
            for (SemanticTemplateIntent intent : SemanticTemplateIntent.values()) {
                Optional<TemplateMapping> mapping = stored.stream().filter(m -> m.intent() == intent).findFirst();
                entries.add(new Entry(intent, mapping, mapping.isPresent() && sendable(mapping.get(), sender)));
            }
            return entries;
        });
    }

    public Entry put(SemanticTemplateIntent intent, Precondition precondition, String providerTemplateName,
            String language, List<String> parameterNames, boolean enabled) {
        Objects.requireNonNull(intent, "Intent is required");
        authorization.requirePermission(TenantPermission.TEMPLATE_WRITE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        return writes.execute(status -> {
            TemplateProviderGateway.Sender sender = gateway.currentSender()
                    .orElseThrow(() -> new TemplateRefusedException(TemplateErrorCode.INTEGRATION_DISABLED));
            Optional<TemplateMapping> existing = repository.find(tenantId, intent);
            TemplateMapping saved;
            if (existing.isEmpty()) {
                if (!(precondition instanceof Create)) {
                    throw new TemplateRefusedException(TemplateErrorCode.STALE_VERSION);
                }
                TemplateMapping created = validated(() -> TemplateMapping.create(tenantId, sender.integrationId(),
                        intent, providerTemplateName, language, parameterNames, enabled, clock.instant()));
                repository.insert(created);
                saved = created;
            } else {
                TemplateMapping current = existing.get();
                if (!(precondition instanceof Version(long expected)) || current.version() != expected) {
                    throw new TemplateRefusedException(TemplateErrorCode.STALE_VERSION);
                }
                TemplateMapping next = validated(() -> current.edit(sender.integrationId(), providerTemplateName,
                        language, parameterNames, enabled, clock.instant()));
                long stored = repository.updateMapping(next, expected)
                        .orElseThrow(() -> new TemplateRefusedException(TemplateErrorCode.STALE_VERSION));
                saved = next.withVersion(stored);
            }
            audit.updated(saved.id(), saved.enabled());
            return new Entry(intent, Optional.of(saved), false);
        });
    }

    /**
     * The explicit verification path: asks the provider, stores only its normalized answer against the current
     * revision, and never advances the mapping version. An unobtainable answer drops stale evidence and is reported
     * as {@code PROVIDER_UNAVAILABLE} after that downgrade is committed.
     */
    public Entry refreshApproval(SemanticTemplateIntent intent, long expectedVersion) {
        Objects.requireNonNull(intent, "Intent is required");
        authorization.requirePermission(TenantPermission.TEMPLATE_WRITE);
        TenantId tenantId = contexts.requireCurrent().tenantId();
        TemplateMapping current = reads.execute(status -> repository.find(tenantId, intent))
                .orElseThrow(() -> new TemplateRefusedException(TemplateErrorCode.NOT_FOUND));
        if (current.version() != expectedVersion) {
            throw new TemplateRefusedException(TemplateErrorCode.STALE_VERSION);
        }
        TemplateProviderGateway.Sender sender = gateway.currentSender()
                .orElseThrow(() -> new TemplateRefusedException(TemplateErrorCode.INTEGRATION_DISABLED));
        TemplateProviderGateway.Lookup lookup = gateway.lookup(current.providerTemplateName(), current.language());
        TemplateMapping next = switch (lookup) {
            case TemplateProviderGateway.Reported reported ->
                    current.verified(reported.status(), reported.businessAccountId(), clock.instant());
            case TemplateProviderGateway.Absent absent ->
                    current.verified(TemplateApprovalStatus.NOT_FOUND, absent.businessAccountId(), clock.instant());
            case TemplateProviderGateway.Unavailable unavailable -> current.unverified(clock.instant());
        };
        TemplateMapping saved = writes.execute(status -> {
            if (!repository.updateApproval(next, expectedVersion, current.approval())) {
                throw new TemplateRefusedException(TemplateErrorCode.STALE_VERSION);
            }
            if (next.approval().status() != current.approval().status()) {
                audit.updated(next.id(), next.enabled());
            }
            return next;
        });
        if (lookup instanceof TemplateProviderGateway.Unavailable) {
            throw new TemplateRefusedException(TemplateErrorCode.PROVIDER_UNAVAILABLE);
        }
        return new Entry(intent, Optional.of(saved), sendable(saved, Optional.of(sender)));
    }

    private boolean sendable(TemplateMapping mapping, Optional<TemplateProviderGateway.Sender> sender) {
        return sender.isPresent() && mapping.sendable(sender.get().integrationId(),
                sender.get().businessAccountId(), clock.instant(), properties.approvalMaxAge());
    }

    /** Only domain validation maps to INVALID_INPUT; other failures stay server errors. */
    private static TemplateMapping validated(Supplier<TemplateMapping> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException invalid) {
            throw new TemplateRefusedException(TemplateErrorCode.INVALID_INPUT);
        }
    }
}
