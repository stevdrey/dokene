# ADR 0028: Manual Dispatch Claim and Last-Moment Send Gate

## Status

Accepted. Implements `POST /api/messages/{messageId}/sends` as frozen by [ADR 0023](0023-phase-3-outbound-messaging-contracts.md) §3.1, §4.2 to §4.4 and [ADR 0024](0024-phase-3-messaging-contract-freeze.md) §2 and §5. Issue: [#138](https://github.com/stevdrey/dokene/issues/138), part of epic [#130](https://github.com/stevdrey/dokene/issues/130). Delivered in two pull requests. The first adds the claim, the gate, the synchronous dispatch and migration V25. The second adds the bounded recovery processor described in §5.

Live sending stays off. The global kill switch is hardcoded to `OUTBOUND_DISABLED_GLOBALLY` until [#144](https://github.com/stevdrey/dokene/issues/144). Before anyone enables it, [#218](https://github.com/stevdrey/dokene/issues/218) (provider template component-schema verification) must also be merged, because the registry proves only status, name and language (ADR 0025).

## Context

An approved message has to reach the provider at most once per human send command. Every policy that can change after approval has to be re-checked immediately before the call: kill switches, the integration, consent, do-not-contact, archive, cadence, the template mapping and the sending identity. The provider call cannot be rolled back, and Meta offers no client idempotency token (ADR 0027). So the application has to make the decision to call durable before calling, and has to keep an ambiguous result ambiguous rather than guess.

## Decision

### 1. The STARTED attempt row is the durable intent and claim

The route has no request body. Any payload, even `{}`, is refused with `400 INVALID_INPUT` before anything happens, so a client can never believe that conditions it sent were applied.

No separate outbox table. One transaction, under the customer lock and then the message lock, does all of the following:

- takes the `(tenant, SEND, key)` idempotency lock;
- checks the `If-Match` version;
- runs the full send policy (§2);
- applies T6 (`APPROVED → QUEUED`, `SEND_REQUESTED`, audited `MESSAGE_SEND_REQUESTED`) and T7 (`QUEUED → SENDING`, `SEND_ATTEMPT_STARTED`);
- inserts the `outbound_send_attempts` row in `STARTED`;
- records the idempotency key with the attempt id as its child record.

The attempt row carries the authorizing membership and actor, the message's `send_key` and the attempt number. That makes it the authorized dispatch intent. Its commit is the claim.

Duplicate suppression is layered:

| Case | What stops the second send |
| --- | --- |
| Same key, concurrently or later | The advisory lock serializes the requests. The second one replays the current representation (`200`) without calling the provider. A different message or version under the same key is `409 IDEMPOTENCY_KEY_REUSED`. |
| Different keys, concurrently | The customer lock and the version-predicated update. The loser sees `STALE_VERSION` or `INVALID_TRANSITION` and makes no call. |
| Any writer that bypasses the checks above | The partial unique index `one_started_attempt_per_message` (V25). It allows at most one `STARTED` attempt per message, and a violation surfaces as `INVALID_TRANSITION`. |
| A later request while a claim is open | T6 requires `APPROVED`. A message that is `SENDING`, whether its outcome is pending or unknown, cannot be sent again (ADR 0023 §4.2). |

### 2. Last-moment gate

`SendPolicyGate` runs inside the claim transaction, under both locks, immediately before T6. The provider call follows that commit with no further decision in between. Checks run in ADR 0023 §4.3 order and fail closed:

1. The trusted tenant context and `MESSAGE_SEND`, including resource access to the customer. The request context was resolved before the claim, and authorization trusts its cached role. So the claim re-reads the membership under the customer lock through `CurrentMembershipAuthorizer` (`tenant`), which re-resolves the member with `TenantContextResolver`. Submit, approve, reject and cancel (T1 to T5) use the same check under their customer lock, so a revocation or demotion committed before any of these commands stops it. It refuses with `FORBIDDEN` if the membership is no longer active, is no longer the same membership (a member who was removed and re-invited does not count), or its role no longer holds `MESSAGE_SEND`. Each such refusal is audited as `AUTHORIZATION_DENIED` (`MESSAGE_SEND`, reason `INACTIVE_MEMBERSHIP` or `INSUFFICIENT_PERMISSION`) through the authorization audit listener. That record commits on its own, even though the claim rolls back (ADR 0023 §4.5). The fresh context then authorizes and attributes the rest of the claim, and it is the context the provider call runs under.
2. `OutboundSafetyControl.evaluate`: the global switch, the tenant switch, then an enabled, ownership-verified integration. This yields the current sender.
3. The message is `APPROVED`, and its approval bound a fingerprint.
4. The contact still belongs to the customer. The customer is active, has no do-not-contact flag, has WhatsApp consent granted for that contact, is `DUE` or `OVERDUE`, and the body still passes `DraftSafetyValidator`. These are the T1/T2 guards, now in `MessageSendGuards` and shared by all three steps.
5. The documented abuse bounds (ADR 0024 §5), configured under `dokene.messaging`:
   - `CONTACT_FREQUENCY_EXCEEDED` when another message to the same customer has `sent_at` within `min-contact-interval` (default 24h);
   - `RATE_LIMITED` (`429`) when the tenant has started `send-rate-per-tenant-per-minute` attempts (default 30) in the last minute, counted under a per-tenant advisory lock so concurrent sends cannot all pass the count. This bound is checked last (`SendPolicyGate.requireRateBudget`), after every other step, just before the claim writes. The claim takes the lock only then and reads the clock after taking it, so the lock covers only the count and the inserts, never the rest of the policy. A claim that waited behind another one counts the window, and dates its attempt (`started_at`), from when it actually holds the budget. The response carries `Retry-After`: the whole seconds, rounded up, until the oldest counted attempt leaves the window.
6. `ApprovalBindingAssembler.bind`. It re-resolves the template through `TemplateMappingResolver` (`Resolved` only: enabled, provider-approved for the current mapping version and business account, not older than `dokene.template.approval-max-age`). It re-renders the parameters, validates them with `TemplateParameterValues.validate` and reads the current consent revision.
7. Step 5b: the stored approval fingerprint must cover that binding, otherwise `APPROVAL_INVALIDATED`. A later mapping edit, disable, rejection, expiry, credential rotation or consent re-grant therefore voids the approval.
8. `attemptCount < max-send-attempts` (default 3), otherwise `SEND_ATTEMPTS_EXHAUSTED`.

The gate returns the `OutboundSendCommand` built from the binding that matched: exactly the bound parameter values, the message's `send_key` and the bound `SenderRevision`. Any refusal rolls the whole transaction back: no state change, attempt row, idempotency key or send audit row is written, and **zero provider calls** are made.

### 3. Provider call and outcome

The call runs outside any transaction, so no connection or row lock is held across the network. A port result maps through the aggregate (ADR 0027):

- `Accepted` → `SENT`, with the cadence touch;
- `RejectedPermanently` → `FAILED`;
- `FailedTransiently` → back to `APPROVED`, or `FAILED` at the attempt cap;
- `OutcomeUnknown` → stays `SENDING` with `outcomeUnknown`.

The call runs under a dispatch deadline, `dokene.messaging.send-timeout` (default 10s, ADR 0023 §7), that covers the adapter's preparation, secret resolution and HTTP exchange. The call runs on a virtual thread that binds the member's context explicitly. If it misses the deadline, it is interrupted and the result is `OutcomeUnknown(empty)`. A late answer is ignored, never recorded as success, because the send may still have landed. An exception, an `Error` or a `null` that escapes the port despite its contract is also treated as `OutcomeUnknown(empty)`, immediately, because the failure point is unknown. If the operator's request thread is interrupted while it waits, the outcome is still recorded as unknown first, and the interrupt is restored only after that, because the pool and JDBC refuse an interrupted thread. The Graph adapter's own timeout must be strictly shorter than `send-timeout`, and `DispatchDeadlineCheck` fails startup otherwise, so a slow but definitive Meta answer is read rather than cut off as unknown. A second transaction then takes the customer lock and then the message lock, applies T8 to T11, completes the attempt row and records `MESSAGE_SENT`, `MESSAGE_SEND_FAILED` or `MESSAGE_SEND_OUTCOME_UNKNOWN`.

A transiently failed message is back in `APPROVED`. Only a new operator command with a new key sends it again, and that command passes the whole gate again. Nothing retries automatically.

### 4. When the outcome cannot be recorded

If the outcome transaction fails, for example during an audit outage (`AuditPersistenceException`, `503`), it rolls back. The committed claim survives: the message stays `SENDING`, the attempt stays `STARTED`, and the error is logged with ids only. This is the intended halt:

- the application never assumes a rollback undid the send;
- it never resends;
- it never reports success or failure it did not record.

Further sends of that message are refused. §5 turns an expired claim into an unknown outcome, and a human resolves it as `FAILED` (T12) after checking the provider side.

### 5. Recovery processor (second PR of #138)

A bounded, scheduled processor finds `STARTED` attempts older than a claim timeout across tenants. It uses a `SECURITY DEFINER` function that returns only `(tenant_id, message_id)`, in the style of `dokene.resolve_verified_whatsapp_tenant`. For each one it rebuilds the authorizing member's context, after re-validating that the membership is active and holds `MESSAGE_SEND`, and records `OutcomeUnknown`. It never calls the provider, so a crash between the claim and the record can never cause a duplicate. If the membership is no longer valid, or the outcome still cannot be recorded, the claim is left in place and logged. Recurring candidate generation remains Phase 4.

### 6. Attribution and context

The send runs in the member's own request context (ADR 0024 §4). Every audit row of the send is attributed to that member, who authorized it, including the system-driven T8 to T11 rows; `ck_audit_attribution` needs no change. The provider adapter reads that same context. No new trusted context type is introduced in this PR. The recovery processor reuses the authorizing member's identity as described in §5, instead of the membership-less worker context ADR 0024 §4 reserves for Phase 4.

## Consequences

- **Race semantics.** A kill switch, disable, consent revocation or membership change that commits before the claim transaction locks the customer prevents the send. One that commits after the claim cannot retract a call already in flight. It only stops later attempts. The same holds for a provider-side change after acceptance.
- **Not exactly-once delivery.** Application duplicate suppression guarantees at most one provider call per message attempt and no automatic resend. It is not proof of external exactly-once delivery: a call that timed out may still have been delivered, which is why the outcome stays unknown instead of being retried.
- **Synchronous latency.** The operator's request waits for one provider call, bounded by the adapter deadline (ADR 0027).
- `OutboundMessageTestDriver` remains for tests that need to reach specific states. Production sends go only through `OutboundDispatchService`.
