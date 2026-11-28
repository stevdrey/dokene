// ABOUTME: An AdmissionStore held in memory for the budget's unit tests: it keeps the same window and savepoint rules
// ABOUTME: as the JDBC store and writes down every call, so a test can assert the order of the steps.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.admission.AdmissionDecision.Admitted;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.UncategorizedSQLException;

final class InMemoryAdmissionStore implements AdmissionStore, AdmissionStore.Session {
    final List<String> calls = new ArrayList<>();
    final List<Instant> platformRows = new ArrayList<>();
    final Map<TenantId, Instant> pauses = new HashMap<>();
    private final Map<String, List<Instant>> tenantRows = new HashMap<>();
    Runnable onTenantLock = () -> {
    };
    Runnable onGlobalLock = () -> {
    };
    SQLException lockFailure;
    RuntimeException pauseFailure;
    Duration lockWaitsBoundTo;
    Instant lastTenantPruneCutoff;
    Instant lastPlatformPruneCutoff;

    List<Instant> rows(TenantId tenant, AdmissionKind kind) {
        return tenantRows.computeIfAbsent(tenant.value() + ":" + kind, key -> new ArrayList<>());
    }

    @Override
    public AdmissionDecision underSavepoint(SavepointWork work) {
        Map<String, List<Instant>> tenantSnapshot = new HashMap<>();
        tenantRows.forEach((key, rows) -> tenantSnapshot.put(key, new ArrayList<>(rows)));
        List<Instant> platformSnapshot = new ArrayList<>(platformRows);
        AdmissionDecision decision;
        try {
            decision = work.run(this);
        } catch (SQLException failure) {
            restore(tenantSnapshot, platformSnapshot);
            throw new UncategorizedSQLException("admission", null, failure);
        } catch (RuntimeException failure) {
            restore(tenantSnapshot, platformSnapshot);
            throw failure;
        }
        if (!(decision instanceof Admitted)) {
            restore(tenantSnapshot, platformSnapshot);
        }
        return decision;
    }

    private void restore(Map<String, List<Instant>> tenantSnapshot, List<Instant> platformSnapshot) {
        tenantRows.clear();
        tenantRows.putAll(tenantSnapshot);
        platformRows.clear();
        platformRows.addAll(platformSnapshot);
    }

    @Override
    public void pause(TenantId tenant, Instant until) {
        calls.add("pause");
        if (pauseFailure != null) {
            throw pauseFailure;
        }
        pauses.put(tenant, until);
    }

    @Override
    public void boundLockWaits(Duration timeout) {
        calls.add("boundLockWaits");
        lockWaitsBoundTo = timeout;
    }

    @Override
    public void lockTenant(TenantId tenant, AdmissionKind kind) throws SQLException {
        calls.add("lockTenant:" + kind);
        if (lockFailure != null) {
            throw lockFailure;
        }
        onTenantLock.run();
    }

    @Override
    public void lockGlobalSends() {
        calls.add("lockGlobalSends");
        onGlobalLock.run();
    }

    @Override
    public Optional<Instant> pausedUntil(TenantId tenant) {
        calls.add("pausedUntil");
        return Optional.ofNullable(pauses.get(tenant));
    }

    @Override
    public int pruneTenant(TenantId tenant, AdmissionKind kind, Instant cutoff, int batch) {
        calls.add("pruneTenant:" + kind);
        lastTenantPruneCutoff = cutoff;
        return prune(rows(tenant, kind), cutoff, batch);
    }

    @Override
    public int prunePlatform(Instant cutoff, int batch) {
        calls.add("prunePlatform");
        lastPlatformPruneCutoff = cutoff;
        return prune(platformRows, cutoff, batch);
    }

    @Override
    public WindowUsage tenantUsage(TenantId tenant, AdmissionKind kind, Instant windowStart, int limit) {
        calls.add("tenantUsage:" + kind);
        return usage(rows(tenant, kind), windowStart, limit);
    }

    @Override
    public WindowUsage platformUsage(Instant windowStart, int limit) {
        calls.add("platformUsage");
        return usage(platformRows, windowStart, limit);
    }

    @Override
    public void insertTenant(TenantId tenant, AdmissionKind kind, Instant admittedAt) {
        calls.add("insertTenant:" + kind);
        rows(tenant, kind).add(admittedAt);
    }

    @Override
    public void insertPlatform(Instant admittedAt) {
        calls.add("insertPlatform");
        platformRows.add(admittedAt);
    }

    private static int prune(List<Instant> rows, Instant cutoff, int batch) {
        List<Instant> old = rows.stream().filter(row -> !row.isAfter(cutoff)).sorted().limit(batch).toList();
        old.forEach(rows::remove);
        return old.size();
    }

    private static WindowUsage usage(List<Instant> rows, Instant windowStart, int limit) {
        List<Instant> counted = rows.stream().filter(row -> row.isAfter(windowStart)).sorted().toList();
        return new WindowUsage(counted.size(),
                counted.size() >= limit ? counted.get(counted.size() - limit) : null);
    }
}
