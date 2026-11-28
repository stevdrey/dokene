// ABOUTME: The admission budget (ADR 0040): checks the caller's transaction and tenant, then runs the fixed order of
// ABOUTME: switch, lock, pause, prune, counts and inserts through the store. Any state it cannot trust answers no.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Admitted;
import io.github.stevdrey.dokene.admission.AdmissionDecision.Denied;
import io.github.stevdrey.dokene.admission.AdmissionStore.SavepointWork;
import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class DefaultAdmissionBudget implements AdmissionBudget {
    private static final Admitted ADMITTED = new Admitted();

    private final AdmissionProperties settings;
    private final AdmissionPolicy policy;
    private final AdmissionStore store;
    private final SendSwitch sendSwitch;
    private final TenantContextProvider contexts;
    private final DatabaseUnavailability database;
    private final AdmissionTelemetry telemetry;
    private final Clock clock;

    DefaultAdmissionBudget(AdmissionProperties settings, AdmissionPolicy policy, AdmissionStore store,
            SendSwitch sendSwitch, TenantContextProvider contexts, DatabaseUnavailability database,
            AdmissionTelemetry telemetry, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "Settings are required");
        this.policy = Objects.requireNonNull(policy, "Policy is required");
        this.store = Objects.requireNonNull(store, "Store is required");
        this.sendSwitch = Objects.requireNonNull(sendSwitch, "Send switch is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant context provider is required");
        this.database = Objects.requireNonNull(database, "Database unavailability is required");
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Override
    public AdmissionDecision admitSend(TenantId tenant) {
        return admit(AdmissionKind.SEND, tenant, session -> {
            if (!sendSwitch.sendingAllowed(tenant)) {
                return policy.switchOff();
            }
            session.boundLockWaits(settings.lockTimeout());
            session.lockTenant(tenant, AdmissionKind.SEND);
            // Read after the lock is held: the time spent waiting for it must not age the window.
            Instant now = now();
            Optional<Denied> denied = policy.paused(session.pausedUntil(tenant), now);
            if (denied.isPresent()) {
                return denied.get();
            }
            session.pruneTenant(tenant, AdmissionKind.SEND, policy.windowStart(now, AdmissionPolicy.DAY),
                    settings.pruneBatch());
            int minuteLimit = policy.tenantMinuteLimit();
            denied = policy.full(session.tenantUsage(tenant, AdmissionKind.SEND,
                    policy.windowStart(now, AdmissionPolicy.MINUTE), minuteLimit), minuteLimit,
                    AdmissionPolicy.MINUTE, now, policy.tenantMinuteReason());
            if (denied.isPresent()) {
                return denied.get();
            }
            int dayLimit = settings.send().tenantPerDay();
            denied = policy.full(session.tenantUsage(tenant, AdmissionKind.SEND,
                    policy.windowStart(now, AdmissionPolicy.DAY), dayLimit), dayLimit, AdmissionPolicy.DAY, now,
                    AdmissionDenialReason.TENANT_DAY);
            if (denied.isPresent()) {
                return denied.get();
            }
            // Always the tenant lock first, then the global one, so two instances can never wait on each other.
            session.lockGlobalSends();
            Instant admittedAt = now();
            session.prunePlatform(policy.windowStart(admittedAt, AdmissionPolicy.MINUTE), settings.pruneBatch());
            int globalLimit = settings.send().globalPerMinute();
            denied = policy.full(session.platformUsage(policy.windowStart(admittedAt, AdmissionPolicy.MINUTE),
                    globalLimit), globalLimit, AdmissionPolicy.MINUTE, admittedAt,
                    AdmissionDenialReason.GLOBAL_MINUTE);
            if (denied.isPresent()) {
                return denied.get();
            }
            session.insertTenant(tenant, AdmissionKind.SEND, admittedAt);
            session.insertPlatform(admittedAt);
            return ADMITTED;
        });
    }

    @Override
    public AdmissionDecision admitCandidate(TenantId tenant) {
        return admit(AdmissionKind.CANDIDATE, tenant, session -> {
            if (!settings.candidates().enabled()) {
                return policy.switchOff();
            }
            session.boundLockWaits(settings.lockTimeout());
            session.lockTenant(tenant, AdmissionKind.CANDIDATE);
            Instant now = now();
            session.pruneTenant(tenant, AdmissionKind.CANDIDATE, policy.windowStart(now, AdmissionPolicy.DAY),
                    settings.pruneBatch());
            int dayLimit = settings.candidates().tenantPerDay();
            Optional<Denied> denied = policy.full(session.tenantUsage(tenant, AdmissionKind.CANDIDATE,
                    policy.windowStart(now, AdmissionPolicy.DAY), dayLimit), dayLimit, AdmissionPolicy.DAY, now,
                    AdmissionDenialReason.CANDIDATE_DAY);
            if (denied.isPresent()) {
                return denied.get();
            }
            session.insertTenant(tenant, AdmissionKind.CANDIDATE, now);
            return ADMITTED;
        });
    }

    @Override
    public void recordProviderThrottle(TenantId tenant) {
        try {
            if (!inContext(tenant)) {
                telemetry.throttleSignalFailed("TenantMismatch");
                return;
            }
            store.pause(tenant, now().plus(settings.send().throttlePause()));
        } catch (RuntimeException failure) {
            telemetry.throttleSignalFailed(failure.getClass().getSimpleName());
        }
    }

    private AdmissionDecision admit(AdmissionKind kind, TenantId tenant, SavepointWork work) {
        Objects.requireNonNull(tenant, "Tenant is required");
        requireWritableTransaction();
        AdmissionDecision decision = inContext(tenant) ? decide(work) : stateUnavailable();
        telemetry.decided(kind, decision);
        return decision;
    }

    /**
     * The switch and the store can fail in ways that are not data access failures, so every runtime failure answers
     * no. A lost connection is the exception: it keeps its ADR 0029 answer and is raised.
     */
    private AdmissionDecision decide(SavepointWork work) {
        try {
            return store.underSavepoint(work);
        } catch (RuntimeException failure) {
            if (database.isCause(failure)) {
                throw failure;
            }
            return stateUnavailable();
        }
    }

    private Denied stateUnavailable() {
        return new Denied(AdmissionDenialReason.STATE_UNAVAILABLE, database.retryAfter());
    }

    /** Row level security applies the context tenant to every statement, so the argument has to be that tenant. */
    private boolean inContext(TenantId tenant) {
        return contexts.currentTenantId().filter(tenant::equals).isPresent();
    }

    /** A programming error, not a denial: the rows of an admission must live and die with the caller's work. */
    private static void requireWritableTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An admission needs an active transaction");
        }
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("An admission needs a read write transaction");
        }
    }

    /** PostgreSQL keeps microseconds; a finer time would not compare equal to the row it was written to. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
