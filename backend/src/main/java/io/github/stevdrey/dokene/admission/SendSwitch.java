// ABOUTME: Port the messaging module implements so the admission budget can ask whether sending is switched on for a
// ABOUTME: tenant (ADR 0040) without depending on messaging. Read now on every call, never cached.
package io.github.stevdrey.dokene.admission;

import io.github.stevdrey.dokene.tenant.domain.TenantId;

public interface SendSwitch {

    /** False when the global outbound switch, the tenant outbound switch or the integration state blocks sending. */
    boolean sendingAllowed(TenantId tenant);
}
