// ABOUTME: Recurring follow-up candidate generation (issue 460, ADR 0036): one bounded, idempotent scan of a tenant's
// ABOUTME: due queue under a trusted tenant scope. Never approves, advances cadence, dispatches or calls a provider.
package io.github.stevdrey.dokene.followup.application;

import io.github.stevdrey.dokene.followup.domain.FollowUpCandidate;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantRepository;
import io.github.stevdrey.dokene.tenant.domain.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The scan reuses the due queue derivation (ADR 0013), so archived, do-not-contact and unconsented customers, the
 * effective cadence and the tenant's IANA time zone are applied exactly as operators see them. It only inserts candidate
 * rows: the customer policy, its version and every outbound record stay as they were.
 *
 * <p>The caller (the scheduler wiring, issue 463) chooses the tenant and the cadence of scans; this class takes no
 * tenant from ambient state and must start outside any tenant scope. A scan that records a full batch leaves the rest
 * for the next one, which skips the cycles already recorded, so every due cycle is reached in bounded steps.
 */
@Service
@EnableConfigurationProperties(FollowUpCandidateProperties.class)
public class FollowUpCandidateGenerator {
    private static final Logger log = LoggerFactory.getLogger(FollowUpCandidateGenerator.class);

    private final FollowUpCandidateRepository candidates;
    private final TenantRepository tenants;
    private final TenantContextProvider contexts;
    private final FollowUpCandidateProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public FollowUpCandidateGenerator(FollowUpCandidateRepository candidates, TenantRepository tenants,
            TenantContextProvider contexts, FollowUpCandidateProperties properties,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.candidates = Objects.requireNonNull(candidates);
        this.tenants = Objects.requireNonNull(tenants);
        this.contexts = Objects.requireNonNull(contexts);
        this.properties = Objects.requireNonNull(properties);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
    }

    /** One bounded scan of {@code tenantId} at the injected clock's instant. */
    public FollowUpCandidateGeneration generate(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "Tenant ID is required");
        if (contexts.currentTenantId().isPresent()) {
            throw new IllegalStateException("Candidate generation must start without a tenant scope");
        }
        Instant evaluatedAt = clock.instant();
        int batchSize = properties.batchSize();
        FollowUpCandidateGeneration result = contexts.callWithTenantId(tenantId,
                () -> transactions.execute(status -> scan(tenantId, evaluatedAt, batchSize)));
        if (!result.recorded().isEmpty()) {
            log.info("Follow-up candidates recorded; tenantId={}, recorded={}, batchFull={}", tenantId.value(),
                    result.recorded().size(), result.batchFull());
        }
        return result;
    }

    private FollowUpCandidateGeneration scan(TenantId tenantId, Instant evaluatedAt, int batchSize) {
        boolean active = tenants.findById(tenantId).filter(tenant -> tenant.status() == TenantStatus.ACTIVE)
                .isPresent();
        if (!active) {
            return FollowUpCandidateGeneration.inactive(tenantId, evaluatedAt);
        }
        List<FollowUpCandidate> recorded = candidates.recordDueCandidates(tenantId, evaluatedAt, batchSize);
        return new FollowUpCandidateGeneration(tenantId, evaluatedAt, FollowUpCandidateGeneration.Outcome.SCANNED,
                recorded, recorded.size() >= batchSize);
    }
}
