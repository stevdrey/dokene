// ABOUTME: Port the messaging module implements so the admission budget can ask whether sending is switched on for a
// ABOUTME: tenant (ADR 0040) without depending on messaging. Read now on every call, never cached.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.tenant.domain.TenantId;

/**
 * The budget asks inside a savepoint of the caller's transaction and rolls back to it on every denial, this one
 * included. An implementation must therefore only read, and read through JDBC: it must not write, and it must not go
 * through the JPA persistence context, whose flush would put the caller's pending changes inside that savepoint,
 * where a denial would undo them without an error.
 */
public interface SendSwitch {

    /** False when the global outbound switch, the tenant outbound switch or the integration state blocks sending. */
    boolean sendingAllowed(TenantId tenant);
}
