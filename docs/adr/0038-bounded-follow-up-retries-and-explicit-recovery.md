# ADR 0038: Bounded Follow-up Retries and Explicit Recovery

## Status

Accepted. Issue: [#461](https://github.com/stevdrey/dokene/issues/461) (Phase 4 A3), part of epic [#457](https://github.com/stevdrey/dokene/issues/457).

It builds on [ADR 0037](0037-durable-multi-instance-follow-up-work-claiming.md) (work ledger), whose states, fencing and open-item rule stay unchanged.

## Context

The work ledger (A2) records claims, retries and exhaustion but leaves three decisions open: how an attempt's failure is classified, when a retry runs, and what happens to an exhausted item. Follow-up work may lead to an external effect, such as a message reaching a provider. An attempt whose effect is uncertain must never be replayed blindly, and the Phase 3 send reconciliation ([ADR 0030](0030-verified-webhook-inbox-and-delivery-reconciliation.md)) remains the only way to settle a message whose send outcome is unknown. The scheduler (A4 [#463](https://github.com/stevdrey/dokene/issues/463)) and provider health monitoring (B1 [#469](https://github.com/stevdrey/dokene/issues/469)) are developed in parallel, so this decision defines the ports they plug into.

## Decision

1. **Classification.** A `FollowUpWorkHandler` (supplied by A4, later by Phase 5 dispatch) reports one `FollowUpWorkAttemptResult` per attempt:
   - `Completed`;
   - `Transient(PROCESSING_ERROR | DEPENDENCY_UNAVAILABLE)`: no external effect happened and a later attempt may succeed;
   - `Permanent(PROCESSING_ERROR | REJECTED)`: no external effect happened and retrying unchanged cannot succeed. `REJECTED` is a new safe category (V49);
   - `OutcomeUnknown`: an external effect may have happened.

   A handler that cannot prove that no external effect happened must report `OutcomeUnknown`. `FollowUpWorkProcessor` records any exception the handler throws, and a missing result, as `OutcomeUnknown`, never as a retryable failure. It logs only the exception class, never its message.
2. **Bounded retries.** `Transient` goes through `FollowUpWorkLedger.fail` with `retryAt = now + backoff(attempt)`. The backoff is `min(max-backoff, initial-backoff × 2^(attempt−1))` less a random share of at most `jitter` of it (`dokene.follow-up.retry.*`: initial 1 minute, from 1 second to 24 hours; max 30 minutes, up to 24 hours and never below the initial delay; jitter 0.2, from 0 to 1). Attempts stay bounded by `dokene.follow-up.work.max-attempts` (ADR 0037). The ledger exhausts the item on its last attempt, and a safe exhaustion releases the customer. Once a handler reports `DEPENDENCY_UNAVAILABLE`, the batch's remaining claims are released the same way without being attempted. `FollowUpWorkLedger.fail` now refuses `REJECTED` as well as the unknown categories.
3. **Exhaustion without retry.** `Permanent` calls `requireRecovery(claim, failure)`, which exhausts the item at once and releases the customer. `OutcomeUnknown` calls `requireRecovery(claim, OUTCOME_UNKNOWN)`, which keeps the customer's open slot (ADR 0037 decision 2), so no newer follow-up starts for that customer until a member decides. A crash on the last attempt ends as `LEASE_EXPIRED` and is treated the same way.
4. **Provider health.** Before claiming, the processor reads `FollowUpProviderHealth` (implemented by B1). `UNAVAILABLE` claims nothing, so no attempt is spent during an outage. `DEGRADED` and `UNKNOWN` claim one item as a probe. `UNKNOWN` also applies when no monitor is wired or the monitor fails. Health is a hint, never an authorization: the holder still re-checks every gate.
5. **Stale claims.** The handler gets a `Lease`. It must renew the lease while it works and stop at once, with no external effect, when a renewal returns `false`. A stale claim's outcome records nothing (ADR 0037 decision 4). The processor counts it as `stale` and leaves the item to the newer holder.
6. **Explicit, authorized, audited recovery.** `GET /api/follow-up-work/recovery?limit=1..100` (`FOLLOWUP_READ`) lists the tenant's exhausted, non-collapsed items, oldest finish first. Each item shows its ids, due date, state, last safe failure, whether its outcome is unknown, its attempts, its finish instant and its recovery count. `POST /api/follow-up-work/recovery/{candidateId}` (`FOLLOWUP_WRITE`) takes exactly `{"action":"REQUEUE"|"RESOLVE"}` and a strong `If-Match` on the recovery count:
   - `REQUEUE` makes the item `RETRY_DUE` now with a fresh attempt budget. V49's `attempt_base` records the attempts already used; the attempt number keeps growing, so the fencing identity stays monotonic. It is allowed only for a failure known to have had no external effect (`PROCESSING_ERROR`, `DEPENDENCY_UNAVAILABLE`, `REJECTED`), at most `dokene.follow-up.retry.max-requeues` times (default 3, up to 20, 0 disables it), and only while the customer has no other open work and no newer cycle that was already enqueued, finished or not (compared by the database's candidate `recorded_at`), so a superseded cycle is never worked again.
   - `RESOLVE` records the item as `COMPLETED` without another attempt, keeps its last failure for history, and releases the customer. It is the only recovery for an unknown outcome. The member settles the message itself through the Phase 3 reconciliation; recovery never sends, replays or touches a message.

   Each applied decision runs in the member's tenant context under forced RLS and records `FOLLOW_UP_WORK_REQUEUED` or `FOLLOW_UP_WORK_RESOLVED` (customer target, no metadata) in the same transaction, so a failed audit write rolls the decision back. A refused decision returns `409` with one of `STALE_VERSION`, `NOT_EXHAUSTED`, `OUTCOME_UNKNOWN`, `REQUEUE_LIMIT_REACHED` or `CUSTOMER_HAS_NEWER_WORK`, and writes nothing. Repeating an applied decision with the same `If-Match` returns the item again without a second change or audit row, so a retried request is idempotent. A check constraint also refuses a `REQUEUE` row whose failure is unknown.
7. **A record, not an action.** Processing and recovery schedule nothing, approve nothing and send nothing. A4 composes `FollowUpWorkProcessor.process` into its scheduler, disabled by default, with its own bounded tenant rotation and shutdown.

## Consequences

- An outage costs no attempts while B1 reports it. Until B1 is wired, every batch is a single probe claim. This is safe but slow, and it is intended until A4 enables the scheduler with B1 in place.
- Each takeover after a crash still consumes an attempt (ADR 0037). A crash on the last attempt needs a member's `RESOLVE`, because the ledger cannot know whether the crashed holder acted.
- If an outcome write fails after the handler acted (for example, the database is unreachable), the lease expires and another instance may run the handler again. Handlers must therefore make external effects idempotent per candidate, as Phase 3 messages already are through their idempotency keys and single open message per customer.
- A requeue of an item that a newer cycle has already superseded, whether that cycle is still open or already finished, is refused with `CUSTOMER_HAS_NEWER_WORK`. The member resolves the item instead.
- The backoff uses the absolute attempt number, so a requeued item starts near the cap. Requeues are rare, operator-driven and bounded.
- Counts by state, oldest due age and metrics remain with B3 ([#462](https://github.com/stevdrey/dokene/issues/462)), and the dashboard remains with B4 ([#464](https://github.com/stevdrey/dokene/issues/464)). The recovery list is a bounded operator read, not a metric.
- A release that does not know the two new audit event types cannot read a page that contains them (ADR 0006). Rollback is safe before the first recovery decision; after it, only roll forward.
