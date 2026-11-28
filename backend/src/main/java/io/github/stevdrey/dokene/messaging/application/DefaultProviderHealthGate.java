// ABOUTME: The provider health gate (ADR 0038): decides from the shared row, and for a closed state takes the probe
// ABOUTME: lease, runs the one read only probe with no lock held and stores its result. Fails closed in enforce mode.
package io.github.stevdrey.dokene.messaging.application;

import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthStore.LeaseAttempt;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthStore.ProbeApplication;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthStore.Snapshot;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthTelemetry.GateDecision;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthTelemetry.StoreOperation;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderUnavailableException;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.Decision;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.RefusalReason;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In {@code observe} mode the gate only counts what {@code enforce} would have decided: it never refuses, never
 * probes, and a store failure never stops a send. In {@code enforce} mode anything it cannot read or write refuses
 * the send, because a send that goes out while the provider is down ends as an unknown outcome a person must resolve.
 */
@Component
public class DefaultProviderHealthGate implements ProviderHealthGate {
    private static final Logger log = LoggerFactory.getLogger(DefaultProviderHealthGate.class);
    /** The lease was released or lost, so the next sender probes at once. */
    private static final Duration PROBE_AGAIN = Duration.ofSeconds(1);

    private final ProviderHealthStore store;
    private final ProviderHealthPolicy policy;
    private final Mode mode;
    private final ProviderReachabilityProbe probe;
    private final TenantContextProvider contexts;
    private final ProviderHealthTelemetry telemetry;
    private final DatabaseUnavailability database;

    public DefaultProviderHealthGate(ProviderHealthStore store, ProviderHealthPolicy policy,
            ProviderHealthProperties properties, ProviderReachabilityProbe probe, TenantContextProvider contexts,
            ProviderHealthTelemetry telemetry, DatabaseUnavailability database) {
        this.store = Objects.requireNonNull(store, "Store is required");
        this.policy = Objects.requireNonNull(policy, "Policy is required");
        this.mode = Objects.requireNonNull(properties, "Properties are required").mode();
        this.probe = Objects.requireNonNull(probe, "Probe is required");
        this.contexts = Objects.requireNonNull(contexts, "Tenant contexts are required");
        this.telemetry = Objects.requireNonNull(telemetry, "Telemetry is required");
        this.database = Objects.requireNonNull(database, "Database unavailability is required");
    }

    @Override
    public Admission admit(MessagingProviderKey provider, Pass pass) {
        boolean observing = mode == Mode.OBSERVE;
        Snapshot snapshot;
        try {
            snapshot = store.current(provider);
        } catch (RuntimeException failure) {
            // Any failure, a row this instance cannot map included: the store port is not trusted to raise only
            // data access exceptions, because a send must never fail or go out on a state nobody could read.
            if (observing) {
                telemetry.storeFailure(provider, StoreOperation.READ);
                log.warn("Provider health not readable; the send goes on in observe mode; provider={}, errorType={}",
                        provider, failure.getClass().getSimpleName());
                return Admission.ALLOW;
            }
            throw storeUnavailable(provider, StoreOperation.READ, failure);
        }
        Decision decision = policy.decide(snapshot.row(), snapshot.now());
        telemetry.decision(provider, mode, switch (decision) {
            case Decision.Allow _ -> GateDecision.ALLOW;
            case Decision.Refuse _ -> GateDecision.REFUSE;
            case Decision.Probe _ -> GateDecision.PROBE;
        });
        if (observing) {
            return Admission.ALLOW;
        }
        return switch (decision) {
            case Decision.Allow _ -> Admission.ALLOW;
            case Decision.Refuse refused -> throw refuse(provider, refused.reason(), refused.retryAfter());
            case Decision.Probe _ -> {
                if (pass == Pass.AFTER_PROBE) {
                    throw refuse(provider, RefusalReason.REPROBE,
                            policy.retryAfter(snapshot.row(), snapshot.now()));
                }
                yield Admission.PROBE;
            }
        };
    }

    @Override
    public void probe(MessagingProviderKey provider, TenantContext member, SenderRevision sender) {
        Objects.requireNonNull(member, "Member context is required");
        Objects.requireNonNull(sender, "Sender is required");
        LeaseAttempt attempt;
        try {
            attempt = store.takeLease(provider);
        } catch (RuntimeException failure) {
            throw storeUnavailable(provider, StoreOperation.LEASE, failure);
        }
        switch (attempt) {
            case LeaseAttempt.NotNeeded _ -> {
                return;
            }
            case LeaseAttempt.Busy busy -> throw refuse(provider, busy.reason(), busy.retryAfter());
            case LeaseAttempt.Taken taken -> {
                ProviderProbeResult result = run(member, sender);
                // Restored only after the write: JDBC and the pool refuse an interrupted thread, and a result that
                // cannot be stored would leave the lease held until it expires.
                boolean interrupted = Thread.interrupted();
                try {
                    finish(provider, taken, result);
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
    }

    /** Stores the result under the lease the probe started with. A lost lease discards it. */
    private void finish(MessagingProviderKey provider, LeaseAttempt.Taken lease, ProviderProbeResult result) {
        telemetry.probe(provider, result);
        ProbeApplication application;
        try {
            application = store.finishProbe(provider, lease.token(), result);
        } catch (RuntimeException failure) {
            // The lease is not released here. It expires on its own, and until then nobody else probes.
            throw storeUnavailable(provider, StoreOperation.RESULT, failure);
        }
        Snapshot after = application.snapshot();
        switch (application) {
            case ProbeApplication.Superseded _ ->
                    throw refuse(provider, RefusalReason.REPROBE, policy.retryAfter(after.row(), after.now()));
            case ProbeApplication.Applied _ -> {
                switch (result) {
                    case PASSED, PASSED_THROTTLED -> {
                    }
                    case FAILED -> throw refuse(provider, RefusalReason.PROBE_FAILED,
                            policy.retryAfter(after.row(), after.now()));
                    case NO_RESULT -> throw refuse(provider, RefusalReason.NO_CREDENTIAL, PROBE_AGAIN);
                }
            }
        }
    }

    /** The port promises never to throw. Anything that escapes still learned nothing about the provider. */
    private ProviderProbeResult run(TenantContext member, SenderRevision sender) {
        try {
            ProviderProbeResult result = contexts.callWithContext(member,
                    () -> probe.probe(member.tenantId(), sender));
            return result == null ? ProviderProbeResult.NO_RESULT : result;
        } catch (RuntimeException failure) {
            log.warn("Provider health probe raised; no result; errorType={}", failure.getClass().getSimpleName());
            return ProviderProbeResult.NO_RESULT;
        }
    }

    private MessagingProviderUnavailableException refuse(MessagingProviderKey provider, RefusalReason reason,
            Duration retryAfter) {
        telemetry.refusal(provider, reason);
        return new MessagingProviderUnavailableException(retryAfter);
    }

    /**
     * A lost database connection keeps the answer every route gives for it (ADR 0029). Any other store failure, for
     * example a missing grant or a busy row, is this gate's own refusal, so it never surfaces as an internal error.
     */
    private RuntimeException storeUnavailable(MessagingProviderKey provider, StoreOperation operation,
            RuntimeException failure) {
        telemetry.storeFailure(provider, operation);
        if (database.isCause(failure)) {
            return failure;
        }
        log.warn("Provider health store failed; the send is refused; provider={}, operation={}, errorType={}",
                provider, operation, failure.getClass().getSimpleName());
        return refuse(provider, RefusalReason.STORE_UNAVAILABLE, database.retryAfter());
    }
}
