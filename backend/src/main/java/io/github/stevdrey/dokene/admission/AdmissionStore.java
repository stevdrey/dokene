// ABOUTME: Port over the admission tables and their advisory locks (ADR 0040). The budget drives one Session inside a
// ABOUTME: savepoint of the caller's transaction; the store keeps the work on Admitted and undoes it on anything else.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

interface AdmissionStore {

    /**
     * Opens a savepoint in the caller's transaction and runs {@code work} inside it. On {@code Admitted} the caller's
     * {@code lock_timeout} is put back and the savepoint is released, so the rows and the locks last to the commit.
     * On {@code Denied} or a failure the savepoint is rolled back, which undoes every write and the timeout change
     * and frees the budget locks at once; a failure is then raised again.
     */
    AdmissionDecision underSavepoint(SavepointWork work);

    /** Sets or overwrites the tenant's pause in a transaction of its own. */
    void pause(TenantId tenant, Instant until);

    @FunctionalInterface
    interface SavepointWork {
        AdmissionDecision run(Session session) throws SQLException;
    }

    interface Session {
        /** Remembers the caller's {@code lock_timeout} and bounds every lock wait that follows. */
        void boundLockWaits(Duration timeout) throws SQLException;

        void lockTenant(TenantId tenant, AdmissionKind kind) throws SQLException;

        void lockGlobalSends() throws SQLException;

        Optional<Instant> pausedUntil(TenantId tenant) throws SQLException;

        /** Deletes up to {@code batch} of the tenant's rows of this kind admitted at or before {@code cutoff}. */
        int pruneTenant(TenantId tenant, AdmissionKind kind, Instant cutoff, int batch) throws SQLException;

        int prunePlatform(Instant cutoff, int batch) throws SQLException;

        /** The tenant's rows of this kind admitted strictly after {@code windowStart}, measured against {@code limit}. */
        WindowUsage tenantUsage(TenantId tenant, AdmissionKind kind, Instant windowStart, int limit)
                throws SQLException;

        WindowUsage platformUsage(Instant windowStart, int limit) throws SQLException;

        void insertTenant(TenantId tenant, AdmissionKind kind, Instant admittedAt) throws SQLException;

        void insertPlatform(Instant admittedAt) throws SQLException;
    }
}
