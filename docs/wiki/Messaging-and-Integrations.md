# Messaging and Integrations

## Messaging goal

Messaging is an external side effect and must be treated as a controlled boundary.

The initial channel is WhatsApp, but the domain should not be coupled directly to one provider SDK or one transport.

## Provider abstraction

Core application logic should depend on a narrow contract such as:

```text
MessagingProvider
```

An initial adapter can integrate with Meta WhatsApp Cloud API. Future adapters may target other providers or channels without changing follow-up policy.

The provider abstraction should expose application-level semantics, not leak arbitrary provider JSON into the domain.

## Message lifecycle

The message state machine is durable and explicit. [ADR 0023](../adr/0023-phase-3-outbound-messaging-contracts.md) fixes the states and the sixteen transitions; `OutboundMessage` in `backend/src/main/java/io/github/stevdrey/dokene/messaging/domain` is the only place a status changes.

```text
PENDING_APPROVAL
  ↓ approve
APPROVED
  ↓ request send
QUEUED
  ↓ start attempt
SENDING
  ↓ provider accepted
SENT
  ↓ delivery report
DELIVERED
  ↓ delivery report
READ
```

with alternate outcomes:

```text
REJECTED    (operator, from PENDING_APPROVAL)
CANCELLED   (operator, from PENDING_APPROVAL or APPROVED)
FAILED      (permanent provider failure, attempt limit reached, or an operator resolving an unknown outcome)
```

There is no `DRAFT` state. A draft, whether an AI suggestion or operator text, lives only in the browser until the operator submits it; submission creates the message directly in `PENDING_APPROVAL`. Every message is born there, also when a tenant later enables automated sending.

`READ`, `REJECTED`, `CANCELLED` and `FAILED` are terminal. Every other state counts as open, and a partial unique index (`one_open_message_per_customer`) keeps at most one open message per customer per tenant.

Transitions are validated in the aggregate; arbitrary status mutation is refused with `INVALID_TRANSITION`. Each applied transition advances the message version by one and appends one row to the event log, so the latest event sequence number always equals the message version.

## HTTP surface (specs 0002 and 0003)

The routes ADR 0023 §3.1 names for submission and approval are live in `MessageController` (`backend/src/main/java/io/github/stevdrey/dokene/messaging/api`), all under `/api`:

| Route | Permission | Result |
|---|---|---|
| `POST /customers/{customerId}/messages` | `MESSAGE_DRAFT` | `201` with `Location: /api/messages/{id}`; a replay of the same `Idempotency-Key` answers `200` with the current representation and no `Location` |
| `GET /messages` | `MESSAGE_READ` | newest first, filters `status` (repeatable) and `customerId`, `cursor` and `limit` (default 50, max 100); items never carry the body |
| `GET /messages/{id}` | `MESSAGE_READ` | the full detail with `ETag`, including `customerDisplayName`, `contactRemoved`, approval, cancellation and attempts |
| `GET /messages/{id}/events` | `MESSAGE_READ` | the event log; no note, body, phone or provider id appears |
| `POST /messages/{id}/approvals` | `MESSAGE_APPROVE` | `decision` is `APPROVE` or `REJECT`; `201` then `200` on replay |
| `POST /messages/{id}/cancellations` | `MESSAGE_APPROVE` or `MESSAGE_DRAFT` (`anyOf`) | the service re checks the exact permission once it knows the state; `201` then `200` on replay |
| `POST /messages/{id}/sends` | `MESSAGE_SEND` | spec 0003: runs the eleven step send policy, commits the attempt row, calls the provider, then completes; `201` with the detail (`SENT`, `APPROVED` after a transient failure, `FAILED`, or `SENDING` with `outcomeUnknown` when the outcome is unknown) and `200` on replay; `429` with `Retry-After` when the per tenant minute window is spent |

Mutations require `If-Match` with a strong numeric ETag (`"7"`) and an `Idempotency-Key` matching `^[A-Za-z0-9._:-]{1,128}$`. Every refusal on these routes is `application/problem+json` with exactly `type`, `title`, `status` and `code`, where `code` is a `MessagingErrorCode` (`INVALID_INPUT` 400, `FORBIDDEN` 403, `NOT_FOUND` 404, `STALE_VERSION`, `INVALID_TRANSITION`, `MESSAGE_ALREADY_OPEN` and `IDEMPOTENCY_KEY_REUSED` 409, and so on). `MessagingProblemHandler` owns the mapping and counts refusals in `dokene.messaging.refusals{operation,code}`. The shared helpers (`ProblemResponses`, `StrongEtag`, `IdempotencyKeys`) live in `io.github.stevdrey.dokene.web`, a package that depends on no module so later features reuse it. Authorization runs in `RequiredPermissionInterceptor` before any binding, so a caller without the permission gets the problem body and one audited denial no matter how malformed the request is.


### Sends (spec 0003)

`OutboundSendService` (`messaging/application`) owns the send in three phases, each a separate transaction boundary, so a provider call never runs inside a database transaction:

1. **Accept**: policy in order (context, permission, idempotency replay, rate limit, global switch, provider present, tenant gate, message lock and `APPROVED` with an `APPROVE` decision, customer guards, no other open message, contact frequency against the follow up anchor date, template mapped, attempts left, body safe), then T6 and T7, the attempt row and the idempotency record commit together.
2. **Call**: the `MessagingProvider` is called with no transaction and no lock. Anything thrown is an unknown outcome, never a transient failure.
3. **Complete**: relock, re read the attempt, apply T8 to T11, touch the cadence on `SENT`, write the audit row and publish `SendAttemptCompleted`. A failure here leaves the message `SENDING` with a `STARTED` attempt and counts `dokene.messaging.send.attempts{outcome=COMPLETION_FAILED}`.

Three ports still have stand ins until Slice 2 lands: `OutboundGate` (`StubOutboundGate`, `dokene.messaging.outbound-gate.stub.enabled`), `TemplateMappingGate.resolve` (`StubTemplateMappingGate`, lower cased intent name, single parameter `body`) and `MessagingProvider`. The provider is selected by `dokene.messaging.provider`: blank means no provider and every send refuses with `PROVIDER_UNAVAILABLE`; `local` wires `LocalMessagingProvider` from `integration.local`, which logs a `WARN` at startup and decides the outcome from the last two digits of the recipient phone:

| Suffix | Provider result | Delivery reports after commit |
|---|---|---|
| `00` | rejected permanently, `INVALID_RECIPIENT` | none |
| `01` | failed transiently, `RATE_LIMITED` | none |
| `02` | outcome unknown, no id | none (stays `SENDING`) |
| `03` | accepted | `DELIVERED` then `READ` |
| `04` | outcome unknown with an id | `DELIVERED` (the T13 path) |
| other | accepted | `DELIVERED` |

Reports replay through `LocalDeliveryScheduler` only after the send transaction commits (`@TransactionalEventListener(AFTER_COMMIT)`), on a daemon thread, under a `ProviderContext` for the tenant and a fresh audit correlation (`ProviderReportExecution` in `integration`), spaced by `dokene.messaging.local.delivery-delay`. Dropped reports count in `dokene.messaging.local.delivery-dropped{reason}`. The dev stack (`compose.yaml`) runs with the local provider and both switches on; `.env.example` lists every variable, and `scripts/seed-local-qa.sh` seeds a due, consented customer whose phone ends in `55`.

### Tables (V16)

| Table | Purpose | Runtime grants |
| --- | --- | --- |
| `outbound_messages` | One row per message: status, body, recipient snapshot (`contact_id` plus `recipient_phone`, with no foreign key to the contact row so phone corrections still work; readers derive `contactRemoved` from whether the contact row still exists), send key, attempt count, provider message id, timestamps, version. | `SELECT, INSERT, UPDATE` |
| `outbound_message_approvals` | One row per approve or reject decision, with the operator note. | `SELECT, INSERT` |
| `outbound_message_cancellations` | One row per cancellation, with the operator note. | `SELECT, INSERT` |
| `outbound_send_attempts` | One row per provider call, committed before the call and completed after it. | `SELECT, INSERT, UPDATE` |
| `outbound_message_events` | Append only log of every transition and every ignored delivery report. | `SELECT, INSERT` |
| `outbound_message_idempotency_keys` | `(tenant, operation, key)` with a request fingerprint, so a replay returns the first result and a reuse with a different payload is refused. | `SELECT, INSERT` |

All six tables carry forced row level security with the standard five policies, and the runtime role never holds `DELETE`. Operator notes stay in the approval and cancellation rows and never reach the audit log or application logs.

V17 extends the audit log with `MESSAGE_*` event types and closed transition metadata (status from, status to, failure category, attempt number). Delivery reports are audited with tenant only attribution under a provider scoped signed context; no member is credited for a provider callback.

## Idempotent sending

External sends must remain safe under:

- scheduler retries;
- HTTP retries;
- process crashes;
- webhook duplication;
- network timeouts where send outcome is initially ambiguous;
- multiple application instances.

A logical message/follow-up should have a durable idempotency identity so retrying the operation does not accidentally contact the customer twice.

## WhatsApp templates

Business-initiated WhatsApp messaging may require approved provider templates depending on Meta policy and the conversation window.

Dokene should maintain a semantic template layer, for example:

```text
GENERAL_FOLLOW_UP
REPEAT_PURCHASE
RELATED_PRODUCT
SEASONAL_EVENT
DORMANT_CUSTOMER
```

Application code maps allowed semantic templates to tenant/provider template configuration.

The AI must not invent provider template IDs.

## Webhooks

Provider delivery/read/failure callbacks enter through a dedicated untrusted boundary.

Webhook handling should:

1. verify the provider signature/authentication mechanism;
2. validate the expected schema;
3. resolve the owning integration and tenant from trusted stored configuration;
4. deduplicate events;
5. reject unknown provider/integration mappings;
6. persist or enqueue processing before expensive work where appropriate;
7. update message state through valid transitions;
8. create appropriate audit records.

## Integration configuration

An integration record may contain non-secret metadata such as:

- tenant;
- provider type;
- external account/phone-number identifiers;
- enabled/disabled state;
- template mappings;
- webhook metadata;
- secret reference;
- creation/update timestamps.

Reusable credentials should live in a secret store or secret-management abstraction, not in ordinary plaintext application columns.

## Meta identifiers

For Meta WhatsApp Cloud API, distinguish clearly between:

- the human-visible WhatsApp phone number;
- Meta's internal phone-number identifier;
- WhatsApp Business Account identifiers;
- access credentials/tokens;
- provider message IDs.

These values have different security and identity semantics and should not be conflated.

## Contact policy

Before every send, deterministic application policy should verify at least:

- recipient belongs to the active tenant context;
- recipient/channel is valid;
- tenant outbound messaging is enabled;
- customer is contact-eligible;
- customer has not opted out;
- contact-frequency rules permit the send;
- actor/automation policy is authorized;
- message has not already been sent;
- selected template/action is allowed;
- integration is enabled and healthy enough to attempt delivery.

## Failure handling

Provider errors should be normalized into useful application categories while preserving safe diagnostic detail.

Do not expose raw secret-bearing provider payloads in logs or UI errors.

Retries should distinguish transient from permanent failures.

## Frontend: Mensajes (spec 0004)

The browser side of Phase 3 lives in `frontend/src/features/messages/` and is reached from the **Mensajes** tab (sidebar group RELACIONES, mobile bottom nav). It is a pure consumer of the routes above: no new table, no backend change.

- **List**: `GET /api/messages` with `limit=50`, opening on **Pendientes** (`PENDING_APPROVAL`). Chips map to status sets in `utils/filters.ts` (**Enviados** covers `QUEUED`, `SENDING`, `SENT`, `DELIVERED`, `READ`; **Rechazados y cancelados** covers `REJECTED`, `CANCELLED`). Rows mask the phone to its last four digits; **Cargar más** follows `nextCursor`. A link from the AI panel after `MESSAGE_ALREADY_OPEN` opens the list on **Todos** filtered by `customerId`.
- **Detail**: `GET /api/messages/{id}` and `/events` in parallel. The strong numeric `ETag` is the only version source for `If-Match`; a detail without one is an error state, never version 0. The timeline hides `applied: false` rows and attaches `approval.note` to the `APPROVED`/`REJECTED` event and `cancellation.note` to the `CANCELLED` event by event type. `providerMessageId` is never rendered.
- **Actions**: approve, reject (optional note, 500 UTF-16 units), cancel and send go through inline confirm groups. One intent holds one `Idempotency-Key` (`crypto.randomUUID()` when the group opens); a retry of the same confirm reuses it, a new intent gets a new one. On `409 STALE_VERSION` or `INVALID_TRANSITION` the UI refetches and shows "Este mensaje cambió: ahora está <estado>." without retrying. `429 RATE_LIMITED` disables **Enviar** on every message of the tenant for `Retry-After` seconds (60 when absent).
- **Polling**: only after a send success, while the status is `QUEUED`, `SENDING` (not frozen) or `SENT`: a `setTimeout` chain every 2 s for at most 20 s, one `AbortController` per tick, events refetched when the version changes. It stops on a terminal status, `outcomeUnknown`, an action start, a selection change, unmount or workspace switch. After the budget **Actualizar** remains.
- **Roles**: `canReadMessages` and `canActOnMessages` in `TenantContext.tsx` express the ADR 0023 §5 matrix once. `VIEWER` sees list and detail with "Tu rol solo permite consultar mensajes." and no controls. The backend stays authoritative; a `403` never changes the held detail.
- **AI panel hand off**: `AiAssistantPanel` has exactly one new control, **Enviar a aprobación**, shown only to `canActOnMessages` roles. It resolves the primary phone through `GET /api/customers/{id}` at click time, then calls `POST /api/customers/{id}/messages` with `If-Match` = the queue item's `policyVersion`, `origin: AI_DRAFT` and `locale: es-419`. The key is reused while the draft text is unchanged and rotated when it changes after a refusal. On success the panel locks the textarea, **Regenerar borrador** and **Consultar de nuevo**, shows "Mensaje enviado a aprobación." and **Ver en Mensajes**.
- **Shared client**: `shared/api/httpClient.ts` reads the `problem+json` `code` into `ApiError.code` and a numeric `Retry-After` on 429 into `ApiError.retryAfterSeconds`. Features map codes through `utils/messageLabels.ts`; no feature parses an error body itself. The strong ETag parser lives in `shared/api/etag.ts`.
- **Tenant**: selection and the customer filter are held in `App.tsx` stamped with the tenant id and ignored when it differs from the active workspace; the feature root remounts on `key={tenantId}`.

## Future channels

Potential future channels include:

- email;
- SMS;
- RCS;
- push notifications;
- other business messaging systems.

Adding a channel must not weaken consent, audit, authorization, or idempotency guarantees.
