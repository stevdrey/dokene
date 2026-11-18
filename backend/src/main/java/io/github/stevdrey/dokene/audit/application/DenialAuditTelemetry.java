// ABOUTME: Signals of the denial audit bound (spec 0020): one count per suppressed denial and one per summary write.
// ABOUTME: Tags are closed enum names only; no identity, tenant, header text or resource id ever becomes a tag.
package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;

public interface DenialAuditTelemetry {

    enum SummaryResult { WRITTEN, RETRY, REJECTED }

    /** {@code reason} is the denial's own reason, also when the cause is a full tracker. */
    void suppressed(AuditDenialReason reason, DenialAuditBudget.Cause cause);

    void summary(SummaryResult result);
}
