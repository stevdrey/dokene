# ADR 0038: Provider Health State and the Send Gate's Last Step

## Status

Accepted. Issue: [#469](https://github.com/stevdrey/dokene/issues/469), part of epic [#457](https://github.com/stevdrey/dokene/issues/457). Delivered in two pull requests: [#471](https://github.com/stevdrey/dokene/issues/471) adds the shared state and feeds it from send results with no change for senders, and [#472](https://github.com/stevdrey/dokene/issues/472) adds the refusal and the recovery probe (§5 and §6).

Amends [ADR 0023](0023-phase-3-outbound-messaging-contracts.md): the send policy of §4.3 gains a last step after the eleven it lists, and `503 PROVIDER_UNAVAILABLE` (§4.4) is also returned by that step, with `Retry-After`. Amends [ADR 0028](0028-manual-dispatch-claim-and-last-moment-send-gate.md): the health step runs after `SendPolicyGate.requireRateBudget` and before the claim is written. Both amendments take effect with #472; until then the send flow is unchanged.

## Context

A send attempted while Meta is down ends as an unknown outcome: the message stays `SENDING` and a person has to resolve it (ADR 0028 §3). During an outage every attempt ends that way for as long as the outage lasts. Nothing in the application knows that the last several calls failed, so nothing can refuse the next one before it claims the message.

## Decision

1. **One shared state per provider, in PostgreSQL.** `dokene.provider_health` (V49) holds one row per provider key (`META_WHATSAPP`), seeded `UNKNOWN`. The states are `HEALTHY`, `DEGRADED`, `UNAVAILABLE` and `UNKNOWN`. The row holds states, counters and times only. It is platform state: it has no `tenant_id` and no row level security policy, and the runtime role may only `SELECT` and `UPDATE` it. Every transaction that reads or writes the row takes the time from the database, so instances with clock skew agree.
2. **One pure function owns every state change.** `ProviderHealthPolicy` takes the current row, an event, the time and the settings and returns the next row. No other code changes the state. The same class computes the effective state (a `HEALTHY` or `DEGRADED` row with no signal for `stale-after` reads as `UNKNOWN`) and the send decision.
3. **Signals come from real send exchanges.** The Graph adapter classifies each finished exchange, apart from what it means for the message:

   | What happened | Signal |
   | --- | --- |
   | A 2xx answer, or any 4xx that is not throttling | `SUCCESS` |
   | HTTP 429, or a 4xx carrying a Meta throttle code of `GraphApiSendErrors` | `THROTTLED` |
   | A 3xx or 5xx answer, a read timeout, or an I/O failure after the request started | `OUTAGE` |
   | Connection refused, connect timeout, failed TLS handshake | `OUTAGE` |
   | A local cause before any connection (secret not resolved, integration not sendable, request not buildable, deadline already used up) | none |
   | The dispatch deadline cut the call short, or the call raised | none |

   A tenant's own failure (bad credential, bad recipient, rejected template) is an answer from Meta, so it counts as `SUCCESS` for health. The signal travels beside the send result (`MessagingProvider.Exchange`); the sealed `ProviderSendResult` is unchanged.
4. **A signal is recorded after the send's outcome is final.** `OutboundDispatchService` reports it once the outcome transaction has committed, outside the dispatch deadline and every send lock. The write is its own short transaction that locks the health row with a one second `lock_timeout`. If it cannot run, the failure is logged and counted and the send's outcome stands.
5. **Thresholds.** `outage-threshold` (default 3) `OUTAGE` signals in a row, with no `SUCCESS` between them and all inside `outage-window` (default 2 minutes), give `UNAVAILABLE`. `THROTTLED` gives `DEGRADED` and can never give `UNAVAILABLE`. `recovery-successes` (default 2) successes in a row give `HEALTHY`. Each trip or failed probe starts a cool down that begins at 30 seconds and doubles up to 5 minutes; the doubling resets only on `HEALTHY`.
6. **In `enforce` mode only a probe leaves `UNAVAILABLE` or `UNKNOWN`** (#472). As the last step of the send policy, a send that finds `UNAVAILABLE` inside the cool down is refused with `503 PROVIDER_UNAVAILABLE` and `Retry-After`, before anything is claimed. A send that finds `UNKNOWN`, or `UNAVAILABLE` with the cool down over, first runs one read only Graph call under a lease stored in the row, with no send lock held; a passed probe lets that same request send. Late results of sends already in flight only touch the last signal time. The probe is the first page of the tenant's own phone number list: HTTP 200 or any 4xx passes it (429 as `DEGRADED`), a 3xx or 5xx answer, a timeout or a connection failure fails it, and no network exchange is no result and no state change. The lease end stored in the row is also the lease token, and a result is applied only while the row still stores it. After a passed probe the request runs the whole send policy again exactly once, with the same idempotency key and `If-Match`; a request never probes twice. A health row that cannot be read, a lease that cannot be written or a result that cannot be written refuses the send, with the `Retry-After` of a database outage (ADR 0029); a lost database connection keeps the ADR 0029 answer itself.
7. **`observe` mode never touches a send.** `dokene.messaging.provider-health.mode` is `enforce` (default) or `observe`. In `observe` there is no refusal and no probe, so a send signal stands in for the probe result: `SUCCESS` as passed, `THROTTLED` as passed with throttling, `OUTAGE` as failed.
8. **Telemetry carries closed values only.** Meters `dokene.messaging.provider.health.state` (gauge), `.signals`, `.transitions`, `.store.failures`, `.decisions`, `.refusals` and `.probes`, and one log line per transition, are tagged with the provider key and enum values. In `observe` mode `.decisions` records what `enforce` would have decided. The health table, meters and log lines hold no tenant id, message id, phone number, token or payload.

## Consequences

- With #471 alone and the default `enforce` mode, the state stays at the seeded `UNKNOWN`: send signals cannot leave it and no probe exists yet. Set `observe` to see the state move before #472 lands.
- Every provider exchange adds one small write on a single shared row. This is fine at human driven volume; automatic sending should measure contention on this row before it scales.
- One tenant's exchanges move the state for all tenants. A tenant whose account alone gets 5xx answers can trip it, at the price of leaving `outage-threshold` of its own messages uncertain each time. The growing cool down bounds this.
- A send whose outcome is unknown stays unknown. Nothing in this decision sends, claims again or resolves a message.
- The dispatch deadline measures our own slowness, so a call it cuts short says nothing about the provider and gives no signal.
- Settings outside their bounds, an unknown mode, or a `probe-lease` not longer than the Graph timeout stop the application at startup.

## Out of scope

- Per tenant pacing after a 429 (epic #457, B2).
- Requiring outage signals from two distinct senders before a trip, and admitting one send at a time right after a passed probe.
- An API or dashboard for the state (epic #457, B3 and B4); `ProviderHealthReader` is the port they will read.
