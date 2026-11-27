# ADR 0036: Deterministic Recurring Follow-up Candidates

## Status

Accepted. Issue: [#460](https://github.com/stevdrey/dokene/issues/460) (Phase 4 A1), part of epic [#457](https://github.com/stevdrey/dokene/issues/457).

It builds on [ADR 0012](0012-deterministic-follow-up-eligibility.md) (eligibility) and [ADR 0013](0013-due-follow-up-queue-and-operator-dispositions.md) (due queue), which stay unchanged.

## Context

Phase 4 adds a recurring scheduler. Its first step is to turn "this customer is due" into a durable record that later steps can claim (A2 [#458](https://github.com/stevdrey/dokene/issues/458)), retry (A3 [#461](https://github.com/stevdrey/dokene/issues/461)) and run on a schedule (A4 [#463](https://github.com/stevdrey/dokene/issues/463)). That record must not depend on how often or how many times a scan runs, and creating it must not approve, schedule or send anything.

## Decision

1. **Candidate identity is the due cycle.** A candidate is identified by `(tenant_id, customer_id, due_date)`, unique in `dokene.follow_up_candidates` (V47). `due_date` is the cycle's date in the tenant's calendar as ADR 0013 computes it: an explicit next date, or the latest of the last manual follow-up, dismissal and sent message, or the latest valid purchase, plus the effective cadence. Repeated scans of the same cycle name the same candidate. Anything that moves the anchor (a dismissal, a manual completion, a sent message, a newer purchase, an explicit date) produces a new due date and therefore a new cycle. So does a change of the customer's or tenant's cadence or of the tenant's time zone, because it moves the due date although the anchor stays. A snooze defers the cycle without moving its anchor, so the candidate after the snooze is the same one; consumers must not resolve a candidate for good only because its customer is snoozed or otherwise not due at claim time.
2. **One derivation.** Generation runs the due queue's SQL (`FollowUpDueSql`, shared with `GET /api/follow-up-queue`) inside a single `INSERT … SELECT`. Archived customers, customer-wide do-not-contact, missing granted WhatsApp consent, snoozes, the effective cadence and the tenant's IANA time zone are applied exactly as the operator queue applies them, and the tenant-local date comes from the same statement. Calendar arithmetic happens on tenant-local dates, so daylight-saving days of 23 or 25 hours do not shift a cycle.
3. **Bounded and starvation free.** One scan records at most `dokene.follow-up.candidates.batch-size` candidates (default 100, 1–1000) in `due_date, customer_id` order. Cycles that already have a candidate are excluded before the limit applies, so a full batch never hides later cycles behind earlier ones; the next scan continues with them. The result reports `batchFull` so the scheduler can decide whether to scan again: `true` means more due cycles may remain, while `false` does not prove that none remain, because a concurrent scan may have recorded some of the rows this one skipped. The insert also re-checks in the same statement that the tenant is still active.
4. **Concurrent runs deduplicate in the database.** `ON CONFLICT (tenant_id, customer_id, due_date) DO NOTHING` makes a scan that races another wait for the other's insert and then skip the cycle. Any number of instances scanning the same tenant at once record each cycle once. No advisory lock or leader election is needed for correctness.
5. **Trusted tenant scope only.** `FollowUpCandidateGenerator.generate(TenantId)` must start outside any tenant scope, binds the given tenant with `TenantContextProvider.callWithTenantId` and runs in one transaction under forced RLS. The caller chooses the tenant; no tenant id comes from a request or from ambient job state. Missing, suspended and archived tenants are skipped without a scan (`TENANT_INACTIVE`).
6. **A record, not an action.** A candidate stores ids, the cycle date, the timing source, the effective cadence, the customer policy version and tenant-local date the scan saw, and the scan instant. It stores no name, phone or message content. Generation does not change the customer policy or its version, advance the cadence, approve or create a message, call any provider, or write audit events. The runtime role may only `SELECT` and `INSERT` candidates; claim and completion state belong to the work ledger (A2), which adds its own columns or tables and grants.
7. **No scheduler here.** This decision adds no `@Scheduled` method and no enablement switch. Scheduling, tenant rotation and enablement (disabled by default) belong to A4.

## Consequences

- Candidates reflect eligibility at scan time only. Consent, do-not-contact, archive, approval, authorization and kill-switch checks still run at the last moment before any send (ADR 0028); a candidate never authorizes one.
- A customer can hold more than one open candidate, for example after a cadence or time zone change. The work ledger (A2) must treat a customer's open candidates together, collapsing them or re-evaluating them at claim time, and never count them as separate follow-ups.
- An explicit next date set back to a date that already has a candidate reuses that candidate: that date is the same cycle.
- A scan still evaluates every active customer of the tenant, as the due queue does; the batch bounds what one scan writes, not what it reads. Partitioning the read is a later optimization if tenants grow large.
- Candidates are never deleted by the runtime. Retention and cleanup, if needed, are an operator or migration-role task to be decided with the ledger.

## Extended by ADR 0037

[ADR 0037](0037-durable-multi-instance-follow-up-work-claiming.md) adds the work ledger that claims candidates across instances. It keeps one open item per customer and resolves a customer's older candidates together with the completed one. V48 adds a unique key `(tenant_id, id, customer_id, due_date)` to `follow_up_candidates` so the ledger can reference it, and a `recorded_at` column that the database fills with its own clock on insert, so the ledger can order candidates against claims without relying on instance clocks; candidates are otherwise unchanged.
