# ADR 0040: Shared Admission Budget for Sends and Follow-Up Candidates

## Status

Accepted. Issue: [#459](https://github.com/stevdrey/dokene/issues/459), part of epic [#457](https://github.com/stevdrey/dokene/issues/457). This decision delivers the budget and its port. Nothing calls the port from the send path or the scheduler yet: that wiring is a separate coordinated change, and until it lands the per minute check in `SendPolicyGate.requireRateBudget` ([ADR 0028](0028-manual-dispatch-claim-and-last-moment-send-gate.md)) is the only limit that enforces.

## Context

The only send limit today is one count of a tenant's attempts in the last minute. Nothing caps a tenant per day, nothing caps all tenants together, and nothing stops one tenant from using the whole room the provider gives the platform. Automatic follow-up (epic #457, A4) will also create candidates in volume, and nothing bounds that either. [ADR 0038](0038-provider-health-gate.md) left the pacing of one tenant after a provider 429 to this decision.

Several backend instances run at once, so a limit kept in one instance's memory is not a limit. A limit that cannot read its own state must not admit either.

## Decision

1. **One port decides every budget.** `AdmissionBudget` in the new top level package `admission` has `admitSend(tenant)`, `admitCandidate(tenant)` and `recordProviderThrottle(tenant)`. An admission answers `Admitted` or `Denied(reason, retryAfter)`. A denial is a value, never an exception, and it means "not attempted, try later": it must never count as a failed attempt or feed a retry counter.
2. **The state is event rows in PostgreSQL.** V51 adds `dokene.tenant_budget_admissions` (one row per admitted send or candidate: `tenant_id`, `kind`, `admitted_at`), `dokene.platform_send_admissions` (one timestamp per admitted send, no tenant) and `dokene.tenant_send_pauses` (one row per paused tenant). Every instance counts the same rows. The two tenant tables are under forced row level security with `SELECT`, `INSERT` and `DELETE` (or `UPDATE` for the pause) policies for `dokene_runtime`; the platform table holds only timestamps, so the global count never reads a tenant's rows.
3. **The caller's transaction owns the rows.** An admission runs inside the caller's read write transaction at `READ COMMITTED`. The rows it writes commit or roll back with the caller's work, so a send that is refused later or rolled back burns no budget and no release call exists to forget. Calling without a transaction, or in a read only one, is a programming error and throws.
4. **Limits.** A send is counted against, in order:

   | Limit | Setting (`dokene.admission`) | Default | Reason when full |
   | --- | --- | --- | --- |
   | Tenant, 60 seconds | the smaller of `send.tenant-per-minute` and the tenant's share | 30 | `TENANT_MINUTE`, or `TENANT_SHARE` when the share is smaller |
   | Tenant, 24 hours | `send.tenant-per-day` | 500 | `TENANT_DAY` |
   | All tenants, 60 seconds | `send.global-per-minute` | 120 | `GLOBAL_MINUTE` |

   The share is `global-per-minute` times `send.tenant-share-percent` (default 25), rounded down and never below 1. Lowering the global limit shrinks every tenant's share in step. A candidate is counted only against `candidates.tenant-per-day` (default 200, reason `CANDIDATE_DAY`). Sends and candidates are counted apart. Windows are rolling: a row counts while it is strictly newer than the window, so a row exactly 60 seconds old no longer counts.
5. **Atomic across instances through advisory locks.** A send admission takes the tenant send lock, then the global send lock, always in that order. A candidate admission takes its own tenant candidate lock and no other, so candidate work never makes a tenant's sends wait. The locks are transaction scoped PostgreSQL advisory locks. An admitted caller holds them to its commit; the contract forbids a provider call before that commit and admits one tenant per transaction.
6. **A denial writes nothing and holds nothing.** The switch check and all budget work run under a JDBC savepoint. Every denial rolls back to it, which undoes the prune and the lock timeout change and frees the budget locks at once. The retry delay of a window denial is the time until the row that frees one unit leaves the window, never below one second.
7. **Fail closed.** Lock waits are bounded by `lock-timeout` (default 1 second). A lock not taken in time, a switch check that fails, or any failed read or write of the budget tables answers `Denied` with `STATE_UNAVAILABLE` and the database outage delay of [ADR 0029](0029-database-outage-http-semantics.md). The caller's transaction stays usable and its own `lock_timeout` is unchanged. A lost database connection is not turned into a denial: it is raised and keeps its ADR 0029 answer. An admission whose tenant argument is not the tenant in the current context, or that runs with no tenant in context, is denied before any switch, lock or table is touched.
8. **Switches come first.** A send admission asks the `SendSwitch` port, which messaging implements over `OutboundSafetyControl` (global switch, tenant switch, integration state). A candidate admission needs `candidates.enabled` to be `true`; it is off by default. A switch that is off answers `SWITCH_OFF` with no lock taken and no budget table read or written.
9. **A provider 429 pauses that tenant only.** `recordProviderThrottle(tenant)` writes `paused_until = now + send.throttle-pause` (default 60 seconds) in a transaction of its own. A new signal overwrites the value, so the pause is extended and never stacked. While it lasts, that tenant's sends answer `TENANT_PAUSED`. A signal that cannot be stored is logged and counted and never raised.
10. **Bounded state.** Each admission deletes up to `prune-batch` (default 500) of its own tenant's rows of its kind that are 24 hours old or older, and a send that reaches the global step deletes up to `prune-batch` platform rows that left the minute window.
11. **No identifiers leave the tables.** Every decision increments `dokene.admission.decisions` tagged only with `kind`, `outcome` and `reason`. A denial writes one log line with the kind and the reason, at most one per kind and reason every 10 seconds on an instance, with the count that was left out. `dokene.admission.store.failures` counts throttle signals that could not be stored.
12. **Dependency direction.** `admission` depends on the tenant module and on `database` only. It makes no provider call and has no HTTP surface. An ArchUnit rule with a negative proof enforces this.

## Consequences

- The global lock is held until an admitted caller commits, so send claims of all tenants pass one at a time through the end of the claim transaction. That is fine while that transaction is short and makes no provider call. It is the first thing to revisit if send volume grows by orders of magnitude.
- Until the wiring change lands there are two limiters: the old minute check enforces and this one enforces nothing. `send.tenant-per-minute` reads the existing `DOKENE_MESSAGING_SEND_RATE_PER_TENANT_PER_MINUTE`, so a deployment that tuned it keeps one knob.
- Settings and the global outbound switch are read at startup. During a rolling restart each instance enforces its own value against the shared count, so the loosest value still running is the real limit until the last instance restarts.
- Windows use each instance's clock. Clock drift between instances shifts a window edge by that drift.
- Each admitted send is two inserts and a few indexed counts. A tenant that goes quiet keeps up to one day of rows until its next admission of that kind.
- A day denial can carry a retry delay of many hours.
- For the later wiring: a tenant facing answer must show only `429 RATE_LIMITED` with `Retry-After` for every budget reason and for `STATE_UNAVAILABLE`. The exact reason stays in counters and logs, so no tenant learns about other tenants' traffic.

## Out of scope

- Replacing `SendPolicyGate.requireRateBudget` with `admitSend`, calling `recordProviderThrottle` on a throttled send result, and retiring `dokene.messaging.send-rate-per-tenant-per-minute` (the coordinated wiring change).
- Calling `admitCandidate` from the scheduler (epic #457, A4).
- An API or dashboard for the counters or the pause state (epic #457, B3 and B4).
- A tenant scoped audit entry for budget denials ([ADR 0032](0032-bounded-authorization-denial-audit.md)), to be decided with the wiring.
