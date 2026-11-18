// ABOUTME: In-memory budget of authorization denial audit rows (spec 0020, ADR 0032): a fixed window per key
// ABOUTME: (identity, tenant, reason, permission), a ceiling per identity, and counts of the denials left unwritten.
package io.github.stevdrey.dokene.audit.application;

import io.github.stevdrey.dokene.audit.domain.AuditDenialReason;
import io.github.stevdrey.dokene.tenant.application.TenantContext;
import io.github.stevdrey.dokene.tenant.domain.IdentityId;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import io.github.stevdrey.dokene.tenant.domain.TenantPermission;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether one denial gets its own audit row or is only counted. State is per instance and lost on a hard
 * stop. Every method takes the one lock and does no I/O, so callers write rows and log lines after it returns. Not
 * final so a test can make it fail (the recorder must then write the row as if there were no budget).
 */
public class DenialAuditBudget {
    private final DenialAuditLimitProperties limits;
    private final Clock clock;
    private final Map<Key, Window> keys = new HashMap<>();
    private final Map<Optional<IdentityId>, IdentityWindow> identities = new HashMap<>();
    /**
     * Ended windows with a count, oldest first. Keyed by summary id, so two windows of one key stay separate. A window
     * that a new denial of its key retires joins when that denial arrives, so it can sit up to one sweep interval
     * behind windows that ended after it.
     */
    private final Map<UUID, Window> pending = new LinkedHashMap<>();
    private OverflowBatch openOverflow = new OverflowBatch();
    private OverflowBatch sealedOverflow;

    public DenialAuditBudget(DenialAuditLimitProperties limits, Clock clock) {
        this.limits = Objects.requireNonNull(limits, "Limits are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public enum Cause { KEY_BUDGET, IDENTITY_CEILING, TRACKER_FULL }

    /**
     * {@code cause} is null when the row must be written. {@code first} marks the first suppressed denial of a key
     * window, or the first overflow since the last sweep. {@code identity} is the slot charged, null for the shared one.
     */
    public record Decision(Cause cause, boolean first, IdentityId identity) {
        public boolean suppressed() {
            return cause != null;
        }
    }

    /**
     * One count waiting to be appended under a fixed row id. {@code context} is null without a tenant; {@code reason}
     * and {@code permission} are both null only for the overflow count, which belongs to no key.
     */
    public record Summary(UUID summaryId, TenantContext context, TenantPermission permission,
            AuditDenialReason reason, int suppressedCount, UUID correlationId) {
        public boolean overflow() {
            return reason == null;
        }
    }

    /**
     * The overflow counts to write first and the pending counts, each in the order to write them. A sweep gets at
     * most one overflow count; shutdown can get two, when an earlier one is still waiting to be written.
     */
    public record Drain(List<Summary> overflow, List<Summary> pending) {
    }

    private record Key(IdentityId identity, TenantId tenant, AuditDenialReason reason, TenantPermission permission) {
    }

    private static final class Window {
        private final Key key;
        private final Instant windowEnd;
        private int admitted;
        private int suppressedCount;
        private UUID summaryId;
        private TenantContext context;
        private UUID correlationId;

        private Window(Key key, Instant windowEnd) {
            this.key = key;
            this.windowEnd = windowEnd;
        }

        private Summary summary() {
            return new Summary(summaryId, context, key.permission(), key.reason(), suppressedCount, correlationId);
        }
    }

    private static final class IdentityWindow {
        private final Instant windowEnd;
        private int admitted;

        private IdentityWindow(Instant windowEnd) {
            this.windowEnd = windowEnd;
        }
    }

    private static final class OverflowBatch {
        private int count;
        private UUID correlationId;
        private UUID summaryId;

        private Summary summary() {
            return new Summary(summaryId, null, null, null, count, correlationId);
        }
    }

    /**
     * Charges one denial. {@code context} is the bound tenant context or null; {@code callerIdentity} is used only
     * without a context and may be null, which charges the one slot shared by every caller without an identity.
     * A unit spent on a row stays spent even when the write then fails.
     */
    public synchronized Decision decide(TenantContext context, IdentityId callerIdentity, AuditDenialReason reason,
            TenantPermission permission, UUID correlationId) {
        Objects.requireNonNull(reason, "Denial reason is required");
        Objects.requireNonNull(correlationId, "Correlation ID is required");
        Instant now = clock.instant();
        IdentityId identity = context == null ? callerIdentity : context.identityId();
        Optional<IdentityId> slot = Optional.ofNullable(identity);
        Key key = new Key(identity, context == null ? null : context.tenantId(), reason, permission);

        Window window = keys.get(key);
        if (window != null && ended(window.windowEnd, now)) {
            keys.remove(key);
            retire(window);
            window = null;
        }
        IdentityWindow identityWindow = identities.get(slot);
        if (identityWindow != null && ended(identityWindow.windowEnd, now)) {
            identities.remove(slot);
            identityWindow = null;
        }

        if (window == null) {
            if (trackedWindows() >= limits.maxTrackedKeys()) {
                boolean first = openOverflow.count == 0;
                if (first) {
                    openOverflow.correlationId = correlationId;
                    openOverflow.summaryId = UUID.randomUUID();
                }
                openOverflow.count = increment(openOverflow.count);
                return new Decision(Cause.TRACKER_FULL, first, identity);
            }
            window = new Window(key, now.plus(limits.window()));
            keys.put(key, window);
        }
        if (identityWindow == null) {
            identityWindow = new IdentityWindow(now.plus(limits.window()));
            identities.put(slot, identityWindow);
        }

        boolean keyBudgetLeft = window.admitted < limits.rowsPerKey();
        if (keyBudgetLeft && identityWindow.admitted < limits.rowsPerIdentity()) {
            window.admitted++;
            identityWindow.admitted++;
            return new Decision(null, false, identity);
        }
        boolean first = window.suppressedCount == 0;
        if (first) {
            window.summaryId = UUID.randomUUID();
            window.context = context;
            window.correlationId = correlationId;
        }
        window.suppressedCount = increment(window.suppressedCount);
        return new Decision(keyBudgetLeft ? Cause.IDENTITY_CEILING : Cause.KEY_BUDGET, first, identity);
    }

    /**
     * For the sweep: moves every ended window with a count to the pending list, forgets ended windows without one,
     * and seals the overflow counted so far unless an earlier sealed count is still waiting to be written.
     */
    public synchronized Drain drainEnded() {
        return drain(false);
    }

    /** For shutdown: as {@link #drainEnded()}, and windows still open with a count become pending too. */
    public synchronized Drain drainAll() {
        return drain(true);
    }

    /** The count was appended, or dropped as rejected: stop tracking it. */
    public synchronized void flushed(Summary summary) {
        pending.remove(summary.summaryId());
    }

    /**
     * Shutdown writes the open count without sealing it, so denials can be added while that write runs. Those are
     * kept as a new count under a new row id, with the correlation id of the count they were split from.
     */
    public synchronized void overflowFlushed(Summary summary) {
        if (sealedOverflow != null && sealedOverflow.summaryId.equals(summary.summaryId())) {
            sealedOverflow = null;
        } else if (summary.summaryId().equals(openOverflow.summaryId)) {
            OverflowBatch rest = new OverflowBatch();
            if (openOverflow.count > summary.suppressedCount()) {
                rest.count = openOverflow.count - summary.suppressedCount();
                rest.correlationId = openOverflow.correlationId;
                rest.summaryId = UUID.randomUUID();
            }
            openOverflow = rest;
        }
    }

    /**
     * Open windows plus ended windows waiting for their count to be written; never above the configured maximum.
     * Identity windows are held apart and not counted here: at most one per identity that has an open or pending
     * key window, or had one in the last window length.
     */
    public synchronized int trackedWindows() {
        return keys.size() + pending.size();
    }

    private Drain drain(boolean includeOpen) {
        Instant now = clock.instant();
        List<Window> leaving = new ArrayList<>();
        for (Iterator<Window> open = keys.values().iterator(); open.hasNext();) {
            Window window = open.next();
            if (ended(window.windowEnd, now) || (includeOpen && window.suppressedCount > 0)) {
                open.remove();
                leaving.add(window);
            }
        }
        leaving.sort(Comparator.comparing(window -> window.windowEnd));
        leaving.forEach(this::retire);
        identities.values().removeIf(identityWindow -> ended(identityWindow.windowEnd, now));
        if (sealedOverflow == null && openOverflow.count > 0) {
            sealedOverflow = openOverflow;
            openOverflow = new OverflowBatch();
        }
        List<Summary> overflow = new ArrayList<>();
        if (sealedOverflow != null) {
            overflow.add(sealedOverflow.summary());
        }
        if (includeOpen && openOverflow.count > 0) {
            overflow.add(openOverflow.summary());
        }
        return new Drain(List.copyOf(overflow), pending.values().stream().map(Window::summary).toList());
    }

    private void retire(Window window) {
        if (window.suppressedCount > 0) {
            pending.put(window.summaryId, window);
        }
    }

    private static boolean ended(Instant windowEnd, Instant now) {
        return !now.isBefore(windowEnd);
    }

    /** The count column is an INTEGER; a count stays at its maximum instead of wrapping. */
    private static int increment(int count) {
        return count == Integer.MAX_VALUE ? count : count + 1;
    }
}
