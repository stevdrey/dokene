# ADR 0030: Verified Webhook Inbox and Delivery Reconciliation

## Status

Accepted. This ADR implements `GET`/`POST /webhooks/whatsapp` as frozen by [ADR 0023](0023-phase-3-outbound-messaging-contracts.md) §3.4 and §6. It composes:
- the verifier of the [ADR 0024](0024-phase-3-messaging-contract-freeze.md) issue 143 addendum;
- the routing gate of the ADR 0024 issue 210 addendum.

Issue: [#139](https://github.com/stevdrey/dokene/issues/139), part of epic [#130](https://github.com/stevdrey/dokene/issues/130). Migration V29.

It amends one decision of ADR 0023 §3.4: a report whose provider message id matches no message is **parked briefly** instead of being dropped at once (§4).

## Context

Meta reports `sent`, `delivered`, `read` and `failed` for every message the send adapter (ADR 0027) handed over. Acceptance is not delivery, so these callbacks are the only source of T13 to T16. The callbacks are unauthenticated HTTP requests from the internet. Meta has some specific behaviours:
- it batches up to 1000 updates per request;
- it retries a non-200 answer for days;
- it gives statuses no event id;
- it can deliver a report before the dispatch has committed the provider message id (the race recorded in spec 0001 Follow-up).

The application must:
- never let a callback choose a tenant;
- never lose a report it acknowledged;
- never apply one twice;
- never move a message backwards;
- never store more provider data than it needs.

## Decision

### 1. Route and filter chain

- **`WebhookSecurityConfiguration`** adds a `SecurityFilterChain` for `/webhooks/**`, ordered before the BFF chain. It is:
  - stateless;
  - CSRF protection on, exempting only the exact callback path `/webhooks/whatsapp`, with a token repository that stores no token, so a denied request fails CSRF without starting an `HttpSession` and no path under `/webhooks/` sets a cookie (amended 2026-10-22, [#266](https://github.com/stevdrey/dokene/issues/266));
  - with no session context, request cache, login or logout.
- **Access.** Only `/webhooks/whatsapp` is permitted; any other path under `/webhooks/` is `403`. A session cookie on the route proves nothing (P3-HOOK-11). Every other route keeps the BFF chain with CSRF and the tenant filter.
- **`WhatsAppWebhookController`** handles, in this fixed order:
  1. It reads the body through `BoundedWebhookBodyReader`.
  2. It verifies `X-Hub-Signature-256` over those raw bytes.
  3. Only then does it parse.
- **Outward statuses:**
  - Every `WebhookVerificationException` reason (including `BODY_TOO_LARGE`) is `403`.
  - Every parse refusal is `400`.
  - A recorded or dropped batch is `200` with an empty body.
  - A database or audit failure while recording is not caught; ADR 0029 turns it into `503`, so Meta retries.
- **Bodies.** No response body ever echoes request data.
- **Proxies.** The nginx and Vite proxies forward `/webhooks`. nginx caps the body at 4 MiB, the backend default.

### 2. Strict parsing

`MetaWebhookPayloadParser` reads only a `VerifiedWebhookBody`. It rejects duplicate keys.

**Allowlisted members per level** (anything else is `400 UNEXPECTED_MEMBER`, before any tenant lookup, so a supplied `tenantId` never gets that far; P3-HOOK-04):

| Level | Allowed members |
|---|---|
| Envelope | `object`, `entry` |
| Entry | `id`, `time`, `changes` |
| Change | `field`, `value` |
| Value | `messaging_product`, `metadata`, `statuses`, `messages`, `contacts`, `errors` |
| Metadata | `display_phone_number`, `phone_number_id` |
| Status | `id`, `status`, `timestamp`, `recipient_id`, `recipient_type`, `recipient_logical_id`, `recipient_user_id`, `conversation`, `pricing`, `errors`, `biz_opaque_callback_data`, `internal_1p_only_data` (issue 289 addendum) |

**Recognised but not processed.** The following are counted as `dokene.webhook.dropped{reason=UNSUPPORTED_EVENT}`:
- change fields other than `messages`;
- inbound `messages` and value-level `errors`;
- status values other than the four delivery states.

`contacts` must be an array but is neither read nor counted: it only accompanies inbound messages or statuses (issue 289 addendum).

**Bounds:**
- More than 1000 statuses in one body is `400`. Every status element counts, tracked or not, across all changes, before any is parsed.
- Provider message ids are 1 to 128 characters with no NUL.
- Phone number ids match `^[0-9]{5,32}$`.
- Timestamps are epoch-second strings.

**What survives parsing.** The result holds only `(phoneNumberId, providerMessageId, status, occurredAt, failure)`.
- The failure is the closed `FailureCategory` of the first error's numeric code, through the same table the send adapter uses (`GraphApiSendErrors.categoryFor`). Error entries are not allowlisted: a missing or non-numeric code, a non-object first entry or a non-array `errors` is `UNKNOWN`, never a refusal that would lose the rest of the batch.
- The provider message id is validated by building the messaging `DeliveryStatusReport` at parse time, so one rule decides validity and no stored row can later fail to become a report.
- Error titles, messages and details, recipient phones and identifiers, conversation, pricing and internal provider metadata are never read into it.

### 3. Tenant resolution and the durable inbox

**Tenant resolution.** For each status, `WebhookIngestionService` resolves the tenant only through `VerifiedPhoneNumberMappings.tenantFor(phoneNumberId)` (ADR 0024 issue 210 addendum, invariant 25).
- An unknown, unverified or disabled mapping stores nothing and counts `dropped{reason=UNKNOWN_PHONE_NUMBER}`. The answer is still `200`.
- A tenant that pre-registers another tenant's number therefore never receives its events (P3-HOOK-08 and the #210 acceptance test).

**Inbox insert.** The statuses of each resolved tenant are inserted into `provider_webhook_events` in one transaction per tenant, under that tenant only, as `PENDING` rows.
- The row is typed: tenant, provider, event id, provider message id, delivery status, occurrence time, failure category, state, attempt count, receipt and next attempt times.
- Raw JSON and free text are never stored.
- The route answers `200` only after every status is recorded or dropped. A failure on any insert rolls back that tenant's group and propagates, so Meta redelivers the batch and the groups already committed deduplicate.

**Table.** Tenant RLS is forced, with the five standard policies. The runtime role has `SELECT, INSERT, UPDATE` only.

**Deduplication.**
- **Key.** Meta statuses have no event id, so the event id is `hex(SHA-256("META_WHATSAPP|" + tenantId + "|" + providerMessageId + "|" + status))`. A message reaches each delivery state once, so (tenant, message, state) is the identity.
  - The tenant is part of the key, so a report parked or left `UNMATCHED` in one tenant never blocks the same id and state reported for another.
  - A byte-identical redelivery deduplicates.
  - So does a re-sent state with another timestamp.
  - The key never contains the raw provider message id.
- **Constraint.** `UNIQUE (provider, provider_event_id)`, inserted with `ON CONFLICT DO NOTHING`. A key held by another tenant reads exactly like a duplicate and reveals nothing about it.
- **Counting.** A duplicate is counted `dropped{reason=DUPLICATE}` and causes no state change and no audit row (P3-HOOK-06).

### 4. Applying, parking and resuming

`WebhookInboxProcessor` applies each `PENDING` row in one transaction:
1. It binds a `ProviderContext` for the row's tenant, with a fresh audit correlation and no member.
2. It locks the row `FOR UPDATE SKIP LOCKED`.
3. It builds the `VerifiedWebhookEvent`.
4. It calls `DeliveryWebhookEventHandler`, the only `VerifiedWebhookEventHandler`, which calls `DeliveryStatusSink`.

The inbox update, the transition, its event row and the provider-attributed audit commit or roll back together.

| Sink result | Inbox state | What else happens |
|---|---|---|
| `Applied` | `APPLIED` | — |
| `Ignored` | `IGNORED` | The sink already recorded a non-applied event without audit, so the state never moves backwards and a late failure never erases a proven delivery (P3-HOOK-07). |
| `NotFound` | stays `PENDING` | Rescheduled with a backoff of 30 s doubling to 5 min. |

**Parking (amends ADR 0023 §3.4).** A `NotFound` row stays parked until `received_at + park-window` (default 15 minutes, at most 1 hour). Then it becomes `UNMATCHED`:
- its provider message id is cleared;
- it is counted `dropped{reason=UNMATCHED_MESSAGE}`.

This settles the spec 0001 webhook race: a report that arrives before T8 commits the provider id is applied on a later run.

**Cross-tenant ids.** A provider message id belonging to another tenant is parked in the routed tenant's own inbox, under its RLS. It can never match there, because the sink looks up `(tenant, providerMessageId)` only. It ends `UNMATCHED` with the id cleared. The other tenant is never touched (P3-HOOK-05).

**Retries:**
- Any exception, for example an audit outage, rolls the row back to `PENDING` and backs it off in a separate transaction. The acknowledgement never depends on audit health.
- **Abandoning.** A row still failing once `received_at + abandon-after` has passed (default 1 day, at least 1 hour and the park window) becomes `ABANDONED` with its provider message id cleared, is counted `dropped{reason=PROCESSING_ABANDONED}` and is logged at `ERROR` with ids only. An ordinary outage never reaches that window, but a row that can never be applied stops retrying and is purged like any terminal row.
- The request applies up to `inline-limit` (default 50, range 0 to 1000) of the rows it inserted inline as a best effort, so a large batch does not hold the acknowledgement.
- A scheduled sweeper (`dokene.integration.whatsapp.webhook.inbox.*`: `enabled`, `interval` default 30 s, `batch-size` default 100) resumes everything else, including rows left by a restart and rows beyond the inline limit.
  - Only one instance runs it, through a Postgres advisory lock.
  - It starts without a tenant scope and reads due rows through the security-definer function `dokene.find_due_webhook_events`, which returns `(tenant_id, event_id)` only.

**No sends.** Nothing on this path can create, approve or send a message. `ModuleDependencyArchitectureTest` forbids the webhook package from depending on the command service, the dispatch service or `MessagingProvider`.

### 5. Retention

- Terminal rows (`APPLIED`, `IGNORED`, `UNMATCHED`, `ABANDONED`) are kept for deduplication for `retention` (default 30 days, at least 8 days, beyond Meta's longest documented retry window of 7 days, and at least `abandon-after`).
- The sweeper then deletes them through `dokene.purge_webhook_events`, at most 1000 per run. This is the only way a row leaves the table.
- `PENDING` rows are never purged.
- The table never holds raw provider JSON, error texts, recipient phones, conversation or pricing data. An `UNMATCHED` or `ABANDONED` row holds no provider message id either.

### 6. Delivery history

ADR 0023 §3.1 keeps `GET /api/messages/{messageId}/events` (`MESSAGE_READ`, tenant-scoped) as the only history route. Webhook transitions appear there as `DELIVERY_UPDATED` events with `actorKind=PROVIDER`, applied or not. Inbox rows are internal and are not exposed.

## Alternatives considered

- **Drop unmatched reports at once (ADR 0023 §3.4 as written).** This is simpler and stores nothing for a foreign id. It loses every report that wins the race against T8 and leaves those messages at `SENT` for good. The bounded park keeps only typed fields under the routed tenant's RLS, for minutes.
- **Apply synchronously in the request and answer `503` on any failure.** This needs no sweeper. But an audit outage would make Meta retry for days, and a pending state could never survive a restart.
- **Store the raw payload for replay.** Rejected: invariant 24 keeps raw provider JSON out of persistent state, and the typed fields are enough to reapply.
- **Key dedup on `(message, state, timestamp)`.** This lets a re-sent state with a new timestamp through as a non-applied event with a version bump that conveys nothing.

## Consequences

- Delivery is eventually consistent. A report is applied inline when possible, otherwise within one sweeper interval plus backoff.
- An operator holding an older `ETag` gets `STALE_VERSION` after a webhook transition (P3-APPR-12), as ADR 0023 §4.1 intends.
- Retention adds a scheduled delete. It is bounded per run and touches terminal rows only.
- The `UNSUPPORTED_EVENT` count makes new Meta fields visible without refusing them. A new member at an allowlisted level is refused with `400`, so a Meta schema addition needs an allowlist change. The refusal is logged and Meta retries, so no report is lost while the change ships.

## Out of scope

- Inbound customer messages.
- Template status webhooks.
- The `SENT`/`DELIVERED` open-message window (scope item 11, #198).
- Live callback evidence, which belongs to the final integration run.

## Addendum (issue 289): live status metadata

Live Meta status callbacks (`sent`, `delivered`, `read`) were refused with `400 UNEXPECTED_MEMBER` after signature verification. Their status objects carry three members the allowlist did not name: `recipient_logical_id`, `recipient_user_id` and `internal_1p_only_data`. Every report was lost, and the messages stayed `SENT`.

**Decision: extend the status allowlist explicitly.** The three names join the Status row of §2.
- The parser accepts them and never reads them. They never reach the result, the inbox, logs, audit or any API.
- `recipient_logical_id` and `recipient_user_id` are provider recipient identifiers. Like `recipient_id`, they are never a tenant, message or authorization input. The tenant still comes only from `VerifiedPhoneNumberMappings.tenantFor(phoneNumberId)`.
- `internal_1p_only_data` was observed in Meta's developer event panel and may be specific to Meta's test tooling. It is untrusted provider metadata: allowed so a test or live callback is not refused, and never interpreted.
- Nothing else changes. The raw-body signature check, body and batch bounds, duplicate-key refusal, required delivery facts (`id`, `status`, epoch-second `timestamp`), tenant resolution, dedup and transition ordering are as in §1 to §4.
- Every other unknown member at every level is still `400 UNEXPECTED_MEMBER`. That includes a `tenantId` beside the new members in a status (P3-HOOK-04), so a future Meta addition still needs a deliberate allowlist change.

**Alternative rejected.** Ignoring unknown members at the status level only would survive the next Meta addition without a release. But a smuggled member there would become silently accepted, and P3-HOOK-04 would weaken at one level. The explicit list keeps every accepted name reviewed.

**Refusal detail.** An `UNEXPECTED_MEMBER` refusal now carries the level it was found at (`ENVELOPE`, `ENTRY`, `CHANGE`, `VALUE`, `METADATA` or `STATUS`, a closed enum). It also carries the member name, but only when the name matches `^[a-z0-9_]{1,64}$`. Any other name, `tenantId` included, is written as `<other>`. The controller logs `reason`, `level` and `member` and never a value, so the next Meta schema addition shows up in one log line instead of a payload comparison in Meta's panel. The name comes from a signature-verified body, and the pattern keeps control characters, casing tricks and long input out of the log.

**Unsupported count.** Live status callbacks carry a value-level `contacts` array. Counting it as `UNSUPPORTED_EVENT` would have turned that counter, meant to surface new Meta fields, into constant noise once these callbacks were accepted. `contacts` is still type-checked as an array but is no longer counted (§2).

**Consequence.** The §Consequences note about retries is bounded by Meta's retry window: a refused report is lost once Meta stops retrying. A refusal spike of `UNEXPECTED_MEMBER` after signature verification is therefore an operational signal to review the allowlist promptly.

Verification: [issue 289 record](../verification/issue-289-webhook-live-status-metadata-verification.md).
