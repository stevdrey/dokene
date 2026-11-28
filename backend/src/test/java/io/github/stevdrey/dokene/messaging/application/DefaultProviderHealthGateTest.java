// ABOUTME: Drives the provider health gate (ADR 0038) with a scripted store and probe: each decision, every refusal
// ABOUTME: reason with its retry delay, fail closed store failures in enforce mode, and observe mode never refusing.
package io.github.stevdrey.dokene.messaging.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.stevdrey.dokene.database.DatabaseUnavailability;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthGate.Admission;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthGate.Pass;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthStore.LeaseAttempt;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthStore.ProbeApplication;
import io.github.stevdrey.dokene.messaging.application.ProviderHealthStore.Snapshot;
import io.github.stevdrey.dokene.messaging.domain.ApprovalFingerprint.SenderRevision;
import io.github.stevdrey.dokene.messaging.domain.MessagingErrorCode;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderKey;
import io.github.stevdrey.dokene.messaging.domain.MessagingProviderUnavailableException;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealth;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthPolicy.RefusalReason;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthSettings.Mode;
import io.github.stevdrey.dokene.messaging.domain.ProviderHealthState;
import io.github.stevdrey.dokene.messaging.domain.ProviderProbeResult;
import io.github.stevdrey.dokene.messaging.domain.ProviderSignalKind;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantMembershipId;
import io.github.stevdrey.dokene.tenant.domain.TenantRole;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PermissionDeniedDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.TransactionSystemException;

class DefaultProviderHealthGateTest {
    private static final MessagingProviderKey META = MessagingProviderKey.META_WHATSAPP;
    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00Z");
    private static final Instant TOKEN = NOW.plusSeconds(10);
    private static final Duration DATABASE_RETRY = Duration.ofSeconds(5);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ScriptedStore store = new ScriptedStore();
    private final ScriptedProbe probe = new ScriptedProbe();
    private final RecordingContexts contexts = new RecordingContexts();
    private final TenantContext member = new TenantContext(new TenantId(UUID.randomUUID()),
            new IdentityId(UUID.randomUUID()), new TenantMembershipId(UUID.randomUUID()), TenantRole.OPERATOR);
    private final SenderRevision sender = new SenderRevision(UUID.randomUUID(), "100000000000001",
            "400000000000001", 1);

    // --- the decision inside the send transaction -----------------------------------------------------------------

    @Test
    void aFreshOpenStateIsAllowedAndCounted() {
        store.row = row(ProviderHealthState.HEALTHY, NOW, null, null);

        assertThat(gate(Mode.ENFORCE).admit(META, Pass.FIRST)).isEqualTo(Admission.ALLOW);

        assertThat(decisions("enforce", "ALLOW")).isEqualTo(1);
        assertThat(probe.calls).isEmpty();
    }

    @Test
    void unavailableInsideTheCoolDownIsRefusedWithTheTimeLeftAndNoProbe() {
        store.row = row(ProviderHealthState.UNAVAILABLE, NOW, NOW.plusSeconds(42), null);

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).admit(META, Pass.FIRST));

        assertThat(refusal.code()).isEqualTo(MessagingErrorCode.PROVIDER_UNAVAILABLE);
        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(42));
        assertThat(decisions("enforce", "REFUSE")).isEqualTo(1);
        assertThat(refusals(RefusalReason.COOL_DOWN)).isEqualTo(1);
        assertThat(probe.calls).isEmpty();
        assertThat(store.leases).isZero();
    }

    @Test
    void aLiveLeaseIsRefusedWithTheLeaseTimeLeft() {
        store.row = row(ProviderHealthState.UNKNOWN, null, null, NOW.plusSeconds(7));

        assertThat(refused(() -> gate(Mode.ENFORCE).admit(META, Pass.FIRST)).retryAfter())
                .isEqualTo(Duration.ofSeconds(7));
        assertThat(refusals(RefusalReason.PROBE_IN_FLIGHT)).isEqualTo(1);
    }

    @Test
    void anUnknownStateAsksForAProbeOnceAndRefusesTheSecondTime() {
        store.row = row(ProviderHealthState.UNKNOWN, null, null, null);
        ProviderHealthGate gate = gate(Mode.ENFORCE);

        assertThat(gate.admit(META, Pass.FIRST)).isEqualTo(Admission.PROBE);
        MessagingProviderUnavailableException again = refused(() -> gate.admit(META, Pass.AFTER_PROBE));

        assertThat(again.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(decisions("enforce", "PROBE")).isEqualTo(2);
        assertThat(refusals(RefusalReason.REPROBE)).isEqualTo(1);
    }

    @Test
    void anUnreadableRowRefusesInEnforceModeWithTheDatabaseOutageDelay() {
        store.readFailure = new PermissionDeniedDataAccessException("denied", new SQLException("denied", "42501"));

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).admit(META, Pass.FIRST));

        assertThat(refusal.retryAfter()).isEqualTo(DATABASE_RETRY);
        assertThat(storeFailures("READ")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isEqualTo(1);
    }

    @Test
    void aLostDatabaseConnectionKeepsItsOwnAnswerInsteadOfTheGateRefusal() {
        CannotGetJdbcConnectionException outage = new CannotGetJdbcConnectionException("no connection",
                new SQLTransientConnectionException("pool exhausted"));
        store.readFailure = outage;

        assertThatThrownBy(() -> gate(Mode.ENFORCE).admit(META, Pass.FIRST)).isSameAs(outage);
        assertThat(storeFailures("READ")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isZero();
    }

    @ParameterizedTest
    @EnumSource(Pass.class)
    void observeModeCountsWhatEnforceWouldDecideAndAlwaysAllows(Pass pass) {
        ProviderHealthGate gate = gate(Mode.OBSERVE);

        store.row = row(ProviderHealthState.UNAVAILABLE, NOW, NOW.plusSeconds(42), null);
        assertThat(gate.admit(META, pass)).isEqualTo(Admission.ALLOW);
        store.row = row(ProviderHealthState.UNKNOWN, null, null, null);
        assertThat(gate.admit(META, pass)).isEqualTo(Admission.ALLOW);

        assertThat(decisions("observe", "REFUSE")).isEqualTo(1);
        assertThat(decisions("observe", "PROBE")).isEqualTo(1);
        assertThat(registry.find("dokene.messaging.provider.health.refusals").counters()).isEmpty();
        assertThat(probe.calls).isEmpty();
    }

    @Test
    void observeModeSendsEvenWhenTheRowCannotBeRead() {
        store.readFailure = new DataAccessResourceFailureException("unreadable");

        assertThat(gate(Mode.OBSERVE).admit(META, Pass.FIRST)).isEqualTo(Admission.ALLOW);

        assertThat(storeFailures("READ")).isEqualTo(1);
        assertThat(registry.find("dokene.messaging.provider.health.decisions").counters()).isEmpty();
    }

    /** covers: spec 0023 AC-5. */
    @Test
    void thePassAfterAProbeThatOpenedTheStateIsAllowed() {
        store.row = row(ProviderHealthState.DEGRADED, NOW, null, null);

        assertThat(gate(Mode.ENFORCE).admit(META, Pass.AFTER_PROBE)).isEqualTo(Admission.ALLOW);

        assertThat(registry.find("dokene.messaging.provider.health.refusals").counters()).isEmpty();
    }

    @Test
    void aCoolDownThatStartedWhileTheProbeRanRefusesThePassAfterItWithTheTimeLeft() {
        store.row = row(ProviderHealthState.UNAVAILABLE, NOW, NOW.plusSeconds(42), null);

        MessagingProviderUnavailableException refusal = refused(
                () -> gate(Mode.ENFORCE).admit(META, Pass.AFTER_PROBE));

        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(42));
        assertThat(refusals(RefusalReason.COOL_DOWN)).isEqualTo(1);
        assertThat(refusals(RefusalReason.REPROBE)).isZero();
    }

    /** covers: spec 0023 AC-14. The delay is the configured database outage delay, not a fixed number. */
    @Test
    void theStoreRefusalCarriesWhateverDatabaseOutageDelayIsConfigured() {
        store.readFailure = new PermissionDeniedDataAccessException("denied", new SQLException("denied", "42501"));

        MessagingProviderUnavailableException refusal = refused(
                () -> gate(Mode.ENFORCE, Duration.ofSeconds(9)).admit(META, Pass.FIRST));

        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(9));
    }

    // --- the probe, outside every transaction ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ProviderProbeResult.class, names = {"PASSED", "PASSED_THROTTLED"})
    void aPassedProbeUnderItsLeaseLetsTheSendRunAgain(ProviderProbeResult result) {
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        probe.result = () -> result;

        gate(Mode.ENFORCE).probe(META, member, sender);

        assertThat(probe.calls).containsExactly(new ProbeCall(member.tenantId(), sender, Optional.of(member)));
        assertThat(store.finished).containsExactly(new Finished(TOKEN, result));
        assertThat(probes(result)).isEqualTo(1);
        assertThat(registry.find("dokene.messaging.provider.health.refusals").counters()).isEmpty();
    }

    @Test
    void aFailedProbeRefusesWithTheCoolDownItStarted() {
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        probe.result = () -> ProviderProbeResult.FAILED;
        store.applied = new Snapshot(row(ProviderHealthState.UNAVAILABLE, NOW, NOW.plusSeconds(30), null), NOW);

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(refusals(RefusalReason.PROBE_FAILED)).isEqualTo(1);
        assertThat(probes(ProviderProbeResult.FAILED)).isEqualTo(1);
    }

    @Test
    void aProbeWithNoResultIsStoredToReleaseTheLeaseAndRefusesForOneSecond() {
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        probe.result = () -> ProviderProbeResult.NO_RESULT;

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(store.finished).containsExactly(new Finished(TOKEN, ProviderProbeResult.NO_RESULT));
        assertThat(refusals(RefusalReason.NO_CREDENTIAL)).isEqualTo(1);
    }

    @Test
    void aProbeThatRaisesIsNoResult() {
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        probe.result = () -> {
            throw new IllegalStateException("adapter bug");
        };

        refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(store.finished).containsExactly(new Finished(TOKEN, ProviderProbeResult.NO_RESULT));
    }

    @Test
    void aResultWhoseLeaseWasLostIsDiscardedAndRefusedWithTheLiveLeaseTimeLeft() {
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        probe.result = () -> ProviderProbeResult.PASSED;
        store.superseded = new Snapshot(row(ProviderHealthState.UNKNOWN, null, null, NOW.plusSeconds(9)), NOW);

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(9));
        assertThat(refusals(RefusalReason.REPROBE)).isEqualTo(1);
    }

    @Test
    void aStateThatOpenedMeanwhileNeedsNoProbe() {
        store.lease = LeaseAttempt.NotNeeded::new;

        gate(Mode.ENFORCE).probe(META, member, sender);

        assertThat(probe.calls).isEmpty();
        assertThat(store.finished).isEmpty();
    }

    @Test
    void aLeaseHeldByAnotherRequestRefusesWithoutProbing() {
        store.lease = () -> new LeaseAttempt.Busy(RefusalReason.PROBE_IN_FLIGHT, Duration.ofSeconds(4));

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(4));
        assertThat(refusals(RefusalReason.PROBE_IN_FLIGHT)).isEqualTo(1);
        assertThat(probe.calls).isEmpty();
    }

    @Test
    void aLeaseThatCannotBeWrittenRefusesWithoutProbing() {
        store.lease = () -> {
            throw new DataAccessResourceFailureException("lock timeout");
        };

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(refusal.retryAfter()).isEqualTo(DATABASE_RETRY);
        assertThat(storeFailures("LEASE")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isEqualTo(1);
        assertThat(probe.calls).isEmpty();
    }

    @Test
    void aResultThatCannotBeWrittenRefusesAlthoughTheProbePassed() {
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        probe.result = () -> ProviderProbeResult.PASSED;
        store.finishFailure = new DataAccessResourceFailureException("lock timeout");

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(refusal.retryAfter()).isEqualTo(DATABASE_RETRY);
        assertThat(storeFailures("RESULT")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isEqualTo(1);
    }

    @Test
    void aProbeThatAnswersNothingAtAllIsNoResult() {
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        probe.result = () -> null;

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThat(store.finished).containsExactly(new Finished(TOKEN, ProviderProbeResult.NO_RESULT));
        assertThat(probes(ProviderProbeResult.NO_RESULT)).isEqualTo(1);
    }

    @Test
    void aLostDatabaseConnectionWhileTakingTheLeaseKeepsItsOwnAnswerAndRunsNoProbe() {
        CannotGetJdbcConnectionException outage = new CannotGetJdbcConnectionException("no connection",
                new SQLTransientConnectionException("pool exhausted"));
        store.lease = () -> {
            throw outage;
        };

        assertThatThrownBy(() -> gate(Mode.ENFORCE).probe(META, member, sender)).isSameAs(outage);

        assertThat(storeFailures("LEASE")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isZero();
        assertThat(probe.calls).isEmpty();
    }

    @Test
    void aLostDatabaseConnectionWhileStoringTheResultKeepsItsOwnAnswer() {
        CannotGetJdbcConnectionException outage = new CannotGetJdbcConnectionException("no connection",
                new SQLTransientConnectionException("pool exhausted"));
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        store.finishFailure = outage;

        assertThatThrownBy(() -> gate(Mode.ENFORCE).probe(META, member, sender)).isSameAs(outage);

        assertThat(storeFailures("RESULT")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isZero();
    }

    /** covers: spec 0023 AC-14. A failed commit is not a data access exception, and it must still refuse the send. */
    @Test
    void aLeaseTransactionThatCannotCommitRefusesWithoutProbing() {
        store.lease = () -> {
            throw new TransactionSystemException("commit failed");
        };

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(refusal.retryAfter()).isEqualTo(DATABASE_RETRY);
        assertThat(storeFailures("LEASE")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isEqualTo(1);
        assertThat(probe.calls).isEmpty();
    }

    @Test
    void anInterruptedProbeStoresItsResultWithTheInterruptClearedAndRestoresItAfter() {
        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        probe.result = () -> {
            Thread.currentThread().interrupt();
            return ProviderProbeResult.NO_RESULT;
        };

        try {
            MessagingProviderUnavailableException refusal = refused(
                    () -> gate(Mode.ENFORCE).probe(META, member, sender));

            // JDBC and the pool refuse an interrupted thread, so the write that frees the lease must not see the flag.
            assertThat(store.interruptedWhileFinishing).containsExactly(false);
            assertThat(refusal.retryAfter()).isEqualTo(Duration.ofSeconds(1));
            assertThat(refusals(RefusalReason.NO_CREDENTIAL)).isEqualTo(1);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /** covers: spec 0023 AC-14. A row that cannot be mapped is not a data access exception. */
    @Test
    void aRowThatCannotBeUnderstoodRefusesInEnforceModeInsteadOfFailingTheRequest() {
        store.readFailure = new IllegalArgumentException("No enum constant ProviderHealthState.PAUSED");

        MessagingProviderUnavailableException refusal = refused(() -> gate(Mode.ENFORCE).admit(META, Pass.FIRST));

        assertThat(refusal.retryAfter()).isEqualTo(DATABASE_RETRY);
        assertThat(storeFailures("READ")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isEqualTo(1);
    }

    /** covers: spec 0023 AC-13. */
    @Test
    void observeModeSendsEvenWhenTheRowCannotBeUnderstood() {
        store.readFailure = new IllegalArgumentException("No enum constant ProviderHealthState.PAUSED");

        assertThat(gate(Mode.OBSERVE).admit(META, Pass.FIRST)).isEqualTo(Admission.ALLOW);

        assertThat(storeFailures("READ")).isEqualTo(1);
    }

    @Test
    void aLeaseOrResultWriteThatMeetsARowItCannotUnderstandRefuses() {
        store.lease = () -> {
            throw new IllegalArgumentException("No enum constant ProviderHealthState.PAUSED");
        };
        refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        store.lease = () -> new LeaseAttempt.Taken(TOKEN);
        store.finishFailure = new IllegalArgumentException("No enum constant ProviderHealthState.PAUSED");
        refused(() -> gate(Mode.ENFORCE).probe(META, member, sender));

        assertThat(storeFailures("LEASE")).isEqualTo(1);
        assertThat(storeFailures("RESULT")).isEqualTo(1);
        assertThat(refusals(RefusalReason.STORE_UNAVAILABLE)).isEqualTo(2);
    }

    @Test
    void aProbeWithoutAMemberOrASenderIsRejectedBeforeAnyLeaseIsTaken() {
        ProviderHealthGate gate = gate(Mode.ENFORCE);

        assertThatNullPointerException().isThrownBy(() -> gate.probe(META, null, sender));
        assertThatNullPointerException().isThrownBy(() -> gate.probe(META, member, null));

        assertThat(store.leases).isZero();
        assertThat(probe.calls).isEmpty();
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private ProviderHealthGate gate(Mode mode) {
        return gate(mode, DATABASE_RETRY);
    }

    private ProviderHealthGate gate(Mode mode, Duration databaseRetry) {
        ProviderHealthProperties properties = new ProviderHealthProperties(mode, null, null, null, null, null, null,
                null);
        return new DefaultProviderHealthGate(store, new ProviderHealthPolicy(properties.settings()), properties,
                probe, contexts, new MicrometerProviderHealthTelemetry(registry),
                new DatabaseUnavailability(databaseRetry));
    }

    private static MessagingProviderUnavailableException refused(Runnable action) {
        MessagingProviderUnavailableException refusal = catchThrowableOfType(
                MessagingProviderUnavailableException.class, action::run);
        assertThat(refusal).as("a provider health refusal").isNotNull();
        return refusal;
    }

    /** A row whose last signal, when it has one, is at {@code lastSignalAt}. */
    private static ProviderHealth row(ProviderHealthState state, Instant lastSignalAt, Instant coolDownUntil,
            Instant probeLeaseUntil) {
        return new ProviderHealth(META, state, NOW.minusSeconds(60), lastSignalAt,
                lastSignalAt == null ? null : ProviderSignalKind.SUCCESS, 0, null, 0, coolDownUntil, 0,
                probeLeaseUntil);
    }

    private double decisions(String mode, String decision) {
        return registry.counter("dokene.messaging.provider.health.decisions", "provider", "META_WHATSAPP", "mode",
                mode, "decision", decision).count();
    }

    private double refusals(RefusalReason reason) {
        return registry.counter("dokene.messaging.provider.health.refusals", "provider", "META_WHATSAPP", "reason",
                reason.name()).count();
    }

    private double probes(ProviderProbeResult result) {
        return registry.counter("dokene.messaging.provider.health.probes", "provider", "META_WHATSAPP", "result",
                result.name()).count();
    }

    private double storeFailures(String operation) {
        return registry.counter("dokene.messaging.provider.health.store.failures", "provider", "META_WHATSAPP",
                "operation", operation).count();
    }

    private record Finished(Instant token, ProviderProbeResult result) {
    }

    private record ProbeCall(TenantId tenantId, SenderRevision sender, Optional<TenantContext> context) {
    }

    private static final class ScriptedStore implements ProviderHealthStore {
        ProviderHealth row = ProviderHealth.unknown(META, NOW);
        RuntimeException readFailure;
        Supplier<LeaseAttempt> lease = LeaseAttempt.NotNeeded::new;
        int leases;
        final List<Finished> finished = new ArrayList<>();
        RuntimeException finishFailure;
        final List<Boolean> interruptedWhileFinishing = new ArrayList<>();
        Snapshot applied = new Snapshot(ProviderHealth.unknown(META, NOW), NOW);
        Snapshot superseded;

        @Override
        public Snapshot current(MessagingProviderKey provider) {
            if (readFailure != null) {
                throw readFailure;
            }
            return new Snapshot(row, NOW);
        }

        @Override
        public LeaseAttempt takeLease(MessagingProviderKey provider) {
            leases++;
            return lease.get();
        }

        @Override
        public ProbeApplication finishProbe(MessagingProviderKey provider, Instant token,
                ProviderProbeResult result) {
            interruptedWhileFinishing.add(Thread.currentThread().isInterrupted());
            if (finishFailure != null) {
                throw finishFailure;
            }
            finished.add(new Finished(token, result));
            return superseded != null ? new ProbeApplication.Superseded(superseded)
                    : new ProbeApplication.Applied(applied);
        }
    }

    private final class ScriptedProbe implements ProviderReachabilityProbe {
        final List<ProbeCall> calls = new ArrayList<>();
        Supplier<ProviderProbeResult> result = () -> ProviderProbeResult.PASSED;

        @Override
        public ProviderProbeResult probe(TenantId tenantId, SenderRevision probed) {
            calls.add(new ProbeCall(tenantId, probed, contexts.current()));
            return result.get();
        }
    }

    /** Binds the context only for the duration of the call, as the real provider does. */
    private static final class RecordingContexts implements TenantContextProvider {
        private TenantContext bound;

        @Override
        public Optional<TenantContext> current() {
            return Optional.ofNullable(bound);
        }

        @Override
        public void runWithContext(TenantContext context, Runnable operation) {
            callWithContext(context, () -> {
                operation.run();
                return null;
            });
        }

        @Override
        public <T, X extends Throwable> T callWithContext(TenantContext context, ScopedOperation<T, X> operation)
                throws X {
            TenantContext previous = bound;
            bound = context;
            try {
                return operation.execute();
            } finally {
                bound = previous;
            }
        }

        @Override
        public void runWithTenantId(TenantId tenantId, Runnable operation) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T, X extends Throwable> T callWithTenantId(TenantId tenantId, ScopedOperation<T, X> operation) {
            throw new UnsupportedOperationException();
        }
    }
}
