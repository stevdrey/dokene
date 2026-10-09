# ADR 0023: Phase 3 Outbound Messaging: Module Ownership, Message State Machine, API Contracts and Permission Matrix

## Status

Accepted. Amends [ADR 0013](0013-due-follow-up-queue-and-operator-dispositions.md) (anchor precedence gains an outbound-message anchor) and [ADR 0020](0020-frontend-ai-assistance-panel.md) (the assistance panel may hand a draft to the approval flow). Builds on [ADR 0004](0004-ai-action-gate.md), [ADR 0005](0005-tenant-aware-authorization.md), [ADR 0006](0006-durable-append-only-audit.md), [ADR 0007](0007-oidc-server-side-session.md), [ADR 0010](0010-contact-consent-and-do-not-contact.md), [ADR 0017](0017-deterministic-ai-action-gate.md) and [ADR 0018](0018-constrained-follow-up-message-draft-generation.md). It does not change any existing permission, role mapping, route, or the Backend for Frontend session contract.

## Context

Roadmap Phase 3 ("WhatsApp integration with manual approval") completes the first end-to-end follow-up: an operator takes a draft (AI-generated under ADR 0018 or written by hand), submits it, a human approves it, the application sends it exactly once through a messaging provider, and delivery or failure is tracked. The roadmap lists the required outcomes: a `MessagingProvider` abstraction, a Meta WhatsApp Cloud API adapter, tenant integration configuration, provider template mapping, a message state machine, an explicit approval workflow, idempotent sending, verified and deduplicated webhooks, delivery/failure tracking, an outbound audit trail and an emergency kill switch.

Several durable decisions have to be made before any of that code exists, and they cut across modules:

1. Which module owns the message aggregate, the approval records, the provider port, the provider adapter, the template mapping and the webhook boundary, and in which direction the dependencies run. Three modules named in `docs/wiki/Architecture.md` and `backend/README.md` (`messaging`, `template`, `integration`) do not exist yet.
2. The message lifecycle. `docs/wiki/Messaging-and-Integrations.md` sketches states but does not say who may trigger each transition, what is re-checked, or what happens when the send outcome is unknown.
3. The HTTP contracts. Phases 1 and 2 established conventions (strong numeric `ETag`, `If-Match` with `409 Conflict` on a stale version, `Idempotency-Key` with `201` first and `200` on replay, empty error bodies, `X-Request-Id` correlation). Phase 3 must follow them, and it needs a way to tell a client *why* a transition was refused without free text.
4. The permission matrix. `TenantPermission` already contains `MESSAGE_READ`, `MESSAGE_DRAFT`, `MESSAGE_APPROVE`, `MESSAGE_SEND`, `TEMPLATE_READ`, `TEMPLATE_WRITE`, `INTEGRATION_READ` and `INTEGRATION_MANAGE`, and `TenantRolePermissions` already assigns them. Those assignments were reviewed in ADR 0005 and are relied on by tests and QA seeds; Phase 3 must map every new operation onto the existing vocabulary rather than redesign it.
5. The browser boundary. ADR 0007 and ADR 0014 keep every provider credential server-side and every browser call on the same-origin session cookie with CSRF. Phase 3 adds an inbound path (provider webhooks) that is neither a browser nor a tenant member, and it must not weaken the Backend for Frontend contract.

Constraints inherited from the security invariants: LLM output never causes a side effect (3), every outbound action is authorized, policy-checked, idempotent and auditable (4), consent and do-not-contact always win (5), secrets never land in plaintext business tables, logs or prompts (6), providers and webhooks are untrusted (7), human approval is the default (8), security-sensitive transitions are explicit and validated (9), audit is append-only with closed metadata (11 to 13).

## Decision

### 1. Module ownership and dependency direction

Three new backend modules are introduced under `io.github.stevdrey.dokene`, each with the existing `api` / `application` / `domain` / `persistence` layout. Existing modules keep their responsibilities.

| Module | Owns | Must not own |
| --- | --- | --- |
| `messaging` | The `OutboundMessage` aggregate and its state machine; approval, cancellation and send-attempt records; the deterministic **send policy** (the Phase 3 action gate for messages); the outbound port `MessagingProvider`; the inbound port `DeliveryStatusSink` that webhooks call; message audit event emission; the `/api/messages/**` and `/api/customers/{customerId}/messages` controllers. | Provider HTTP clients, provider credentials, provider payload parsing, template catalogues, follow-up eligibility rules. |
| `template` | The tenant's mapping from each closed `SemanticTemplateIntent` to a provider template reference (provider template name, language, ordered parameter names); mapping versioning and ETags; the `/api/templates/**` controller. | Message bodies, sending, AI drafting. |
| `integration` | Tenant messaging integration configuration (provider type, external account and phone-number identifiers, enabled flag, secret reference, webhook metadata); the secret-reference port `IntegrationSecretResolver`; the Meta WhatsApp Cloud API adapter (`integration.whatsapp`) that implements `MessagingProvider`; the webhook ingestion boundary (`/webhooks/**`), including signature verification, schema validation, tenant resolution and deduplication; the `/api/integrations/**` controller and the tenant-level outbound kill switch. | Deciding whether a customer may be contacted, message state transitions (it only reports delivery facts through the `messaging` inbound port), storing secret values. |

Dependency direction (arrows mean "depends on"):

```text
integration ──> messaging ──> followup ──> customer
                    │             │            │
                    │             └──> ai      │
                    ├──> template              │
                    ├──> customer              │
                    └──> tenant, audit ────────┘
```

- `messaging` depends on `followup` (fresh `FollowUpEvaluation` at submit and send time, and the cadence-advance port described in §4.6), on `customer` (customer status, contact identity, consent, do-not-contact), on `template` (resolved mapping for the intent), on `tenant` (`TenantContext`, `TenantAuthorizationService`) and on `audit`.
- `followup` does **not** depend on `messaging`. The wiki's older sketch (`followup ──> messaging port`) is superseded: the operator enters the send flow through `messaging` endpoints, not through follow-up dispositions, so no cycle exists.
- `integration` depends on `messaging` (it implements the outbound port and calls the inbound port) and on `tenant`/`audit`. Nothing depends on `integration` except Spring wiring. Provider SDK types and raw provider JSON stay inside `integration.whatsapp`.
- `ai` is unchanged. The draft safety validator (`ai.domain.DraftSafetyValidator`, ADR 0018 §4) is reused by `messaging` for hand-written and edited bodies without changing its behaviour; `messaging` depends on `ai.domain` for that type and for the closed `SemanticAction` and `SemanticTemplateIntent` enums.
- The `MessagingProvider` port is application-level: `send(OutboundSendCommand) -> ProviderSendResult`, where the command carries the resolved recipient E.164, provider template reference, ordered parameters, locale and the message `sendKey`, and the result is a sealed type (`Accepted(providerMessageId)`, `RejectedPermanently(FailureCategory)`, `FailedTransiently(FailureCategory)`, `OutcomeUnknown`). Provider exceptions never cross the port; the adapter normalizes them into the closed `FailureCategory` enum (`INVALID_RECIPIENT`, `TEMPLATE_REJECTED`, `RATE_LIMITED`, `PROVIDER_UNAVAILABLE`, `AUTHENTICATION`, `POLICY_VIOLATION`, `UNKNOWN`), with no provider text, headers or identifiers other than the provider message id.

Frontend: a `features/messages/` area owns the approval queue, message detail and send controls, and a `features/integrations/` area owns integration and template configuration screens. The follow-up assistance panel (ADR 0020) gains exactly one new control, "Enviar a aprobación", which calls the create endpoint of §3; approve and send controls never appear in that panel.

### 2. Message state machine

A message is created directly in `PENDING_APPROVAL`. The server stores no `DRAFT` state: drafts remain advisory, client-side text under ADR 0020, and the wiki's `DRAFT` state is retired. Once created, `body`, `action`, `templateIntent`, `contactId` and `locale` are immutable; to change the text the operator cancels and submits a new message. This keeps every approval bound to exactly the text that will be sent.

States: `PENDING_APPROVAL`, `APPROVED`, `QUEUED`, `SENDING`, `SENT`, `DELIVERED`, `READ`, `REJECTED`, `CANCELLED`, `FAILED`.

Terminal states: `READ`, `REJECTED`, `CANCELLED`, `FAILED`. `DELIVERED` and `SENT` are terminal for operator commands and advance only through provider delivery reports. A customer may have at most one **open** message (any non-terminal state, `SENT` and `DELIVERED` included) at a time; this is the Phase 3 contact-frequency guard.

| # | From | Command or event | To | Actor and permission | Guards re-checked at execution | Audit event |
| --- | --- | --- | --- | --- | --- | --- |
| T1 | (none) | `submit` | `PENDING_APPROVAL` | Member with `MESSAGE_DRAFT` | Tenant active; customer `ACTIVE`; no do-not-contact; `contactId` belongs to the customer and has `GRANTED` WhatsApp consent; follow-up status `DUE` or `OVERDUE`; `If-Match` equals the customer follow-up policy version; body passes `DraftSafetyValidator`; template mapping exists and is enabled for `templateIntent`; no open message for the customer. | `MESSAGE_SUBMITTED` |
| T2 | `PENDING_APPROVAL` | `approve` | `APPROVED` | Member with `MESSAGE_APPROVE` | Same as T1 minus `If-Match` on the policy (message `If-Match` instead); mapping still enabled. | `MESSAGE_APPROVED` |
| T3 | `PENDING_APPROVAL` | `reject` | `REJECTED` | Member with `MESSAGE_APPROVE` | Message `If-Match`. | `MESSAGE_REJECTED` |
| T4 | `PENDING_APPROVAL` | `cancel` | `CANCELLED` | Member with `MESSAGE_DRAFT` | Message `If-Match`. | `MESSAGE_CANCELLED` |
| T5 | `APPROVED` | `cancel` | `CANCELLED` | Member with `MESSAGE_APPROVE` | Message `If-Match`. | `MESSAGE_CANCELLED` |
| T6 | `APPROVED` | `send` (request accepted) | `QUEUED` | Member with `MESSAGE_SEND` | Full send policy (§4.3) including both kill switches, integration enabled, approval present, attempt count below `max-send-attempts`. Committed before any provider call. | `MESSAGE_SEND_REQUESTED` |
| T7 | `QUEUED` | dispatcher claims the message | `SENDING` | System, inside the same request in Phase 3 | Send-attempt row inserted with `sendKey` and attempt number; committed before the provider call. | (attempt row only) |
| T8 | `SENDING` | provider `Accepted` | `SENT` | System | Provider message id stored; `sentAt` set; follow-up anchor advanced (§4.6). | `MESSAGE_SENT` |
| T9 | `SENDING` | provider `RejectedPermanently` | `FAILED` | System | Failure category stored on the attempt and the message. | `MESSAGE_SEND_FAILED` |
| T10 | `SENDING` | provider `FailedTransiently` | `APPROVED` | System | Attempt marked failed; message returns to `APPROVED` so an operator may request another send (new `Idempotency-Key`). When the attempt count reaches `max-send-attempts` (default 3) the transition goes to `FAILED` instead. | `MESSAGE_SEND_FAILED` |
| T11 | `SENDING` | provider `OutcomeUnknown` (timeout, connection lost after the request left) | `SENDING` | System | Message stays `SENDING` with `outcomeUnknown=true`. No further provider call is ever made for this message automatically. | `MESSAGE_SEND_OUTCOME_UNKNOWN` |
| T12 | `SENDING` (outcome unknown) | `resolve` with `outcome=FAILED` | `FAILED` | Member with `INTEGRATION_MANAGE` | Message `If-Match`; only `FAILED` may be chosen by a human. A message is never marked `SENT` without a provider message id. | `MESSAGE_SEND_FAILED` |
| T13 | `SENDING` (outcome unknown) | webhook status for the stored provider message id | `SENT` / `DELIVERED` / `FAILED` | System (webhook) | Only possible when the provider returned an id before the connection dropped; otherwise T12 or the Phase 4 reconciliation job applies. | `MESSAGE_DELIVERY_UPDATED` |
| T14 | `SENT` | webhook `delivered` | `DELIVERED` | System (webhook) | Event not already applied (dedup). | `MESSAGE_DELIVERY_UPDATED` |
| T15 | `SENT`, `DELIVERED` | webhook `read` | `READ` | System (webhook) | Dedup. | `MESSAGE_DELIVERY_UPDATED` |
| T16 | `SENT` | webhook `failed` | `FAILED` | System (webhook) | Dedup; failure category stored. | `MESSAGE_DELIVERY_UPDATED` |

Rules that apply to the whole table:

- Any command not listed for the current state is refused with `409 Conflict` and code `INVALID_TRANSITION`. No endpoint accepts a target status as input.
- Out-of-order webhooks are tolerated: a `delivered` after `read`, or a `sent` after `delivered`, is recorded in the event log but does not move the state backwards.
- Every transition is executed under `SELECT ... FOR UPDATE` on the message row and the governing customer row (the same lock ADR 0013 uses), so a concurrent consent revocation, do-not-contact change or archival serializes with submit, approve and send.
- The same person may submit, approve and send when their role grants all three permissions, as `OPERATOR` does today. Separation of duties is a future tenant policy (Phase 5), not a Phase 3 invariant.

### 3. API routes

All `/api/**` routes below are tenant-scoped: they require the BFF session cookie, `X-Tenant-Id` resolved to an active membership, `X-CSRF-TOKEN` on unsafe methods, and the listed permission through `@tenantAuth`. Request and response bodies are explicit DTOs. Identifiers are UUIDs. List endpoints use the opaque cursor and `limit` (1 to 100, default 50) conventions of ADR 0009.

#### 3.1 Messages (`messaging` module)

| Method and path | Permission | Headers | Success | Notes |
| --- | --- | --- | --- | --- |
| `POST /api/customers/{customerId}/messages` | `MESSAGE_DRAFT` | `Idempotency-Key` (required), `If-Match` = customer follow-up policy version (required) | `201 Created`, `Location: /api/messages/{messageId}`, `ETag` = message version; replay `200 OK` | Body: `{ contactId, action, templateIntent, body, locale, origin }`, `origin` in `AI_DRAFT` or `MANUAL`. Executes T1. |
| `GET /api/messages` | `MESSAGE_READ` | | `200 OK` | Filters: `status` (repeatable), `customerId`, `cursor`, `limit`. Ordered `createdAt DESC, messageId DESC`. List items omit `body`. |
| `GET /api/messages/{messageId}` | `MESSAGE_READ` | | `200 OK`, `ETag` | Full representation including `body`, recipient E.164, approval summary, attempt summaries, `providerMessageId` when known. |
| `GET /api/messages/{messageId}/events` | `MESSAGE_READ` | | `200 OK` | Append-only transition and delivery history (state before/after, occurredAt, actor kind `MEMBER`/`SYSTEM`/`PROVIDER`, failure category). No provider payloads. |
| `POST /api/messages/{messageId}/approvals` | `MESSAGE_APPROVE` | `Idempotency-Key`, `If-Match` = message version | `201 Created` with the message representation and new `ETag`; replay `200 OK` | Body: `{ decision: APPROVE \| REJECT, note? }` (note ≤ 500 chars, stored on the approval record, excluded from audit). Executes T2 or T3. |
| `POST /api/messages/{messageId}/cancellations` | `MESSAGE_DRAFT` from `PENDING_APPROVAL`; `MESSAGE_APPROVE` from `APPROVED` | `Idempotency-Key`, `If-Match` | `201 Created`; replay `200 OK` | Body: `{ note? }`. Executes T4 or T5. |
| `POST /api/messages/{messageId}/sends` | `MESSAGE_SEND` | `Idempotency-Key`, `If-Match` | `201 Created` with the message representation after the attempt (status `SENT`, `APPROVED`, `FAILED` or `SENDING`) and new `ETag`; replay `200 OK` returns the original attempt result | Executes T6 to T11 synchronously within one request in Phase 3 (no scheduler). The HTTP status reports that the attempt was recorded, not that the provider accepted; clients read `status` and `lastAttempt.outcome`. |
| `POST /api/messages/{messageId}/resolutions` | `INTEGRATION_MANAGE` | `Idempotency-Key`, `If-Match` | `201 Created`; replay `200 OK` | Body: `{ outcome: FAILED, note? }`. Executes T12 only when `outcomeUnknown` is true. |

Message representation (detail):

```json
{
  "messageId": "…", "customerId": "…", "contactId": "…", "recipientPhone": "+506…",
  "channel": "WHATSAPP", "status": "APPROVED", "outcomeUnknown": false,
  "action": "REPEAT_PURCHASE_FOLLOW_UP", "templateIntent": "…", "locale": "es-419",
  "origin": "AI_DRAFT", "body": "…", "sourcePolicyVersion": 7,
  "approval": { "decision": "APPROVE", "decidedAt": "…", "decidedByMembershipId": "…" },
  "attempts": [ { "attemptNumber": 1, "startedAt": "…", "outcome": "FAILED_TRANSIENT", "failureCategory": "RATE_LIMITED" } ],
  "providerMessageId": null, "sentAt": null, "deliveredAt": null, "readAt": null,
  "createdAt": "…", "updatedAt": "…", "version": 3
}
```

`createdByMembershipId` and `decidedByMembershipId` are membership UUIDs, never identity UUIDs or display names, consistent with ADR 0006 attribution.

#### 3.2 Template mappings (`template` module)

| Method and path | Permission | Headers | Success |
| --- | --- | --- | --- |
| `GET /api/templates` | `TEMPLATE_READ` | | `200 OK`, one entry per `SemanticTemplateIntent` with `{ templateIntent, enabled, providerTemplateName, language, parameterNames[], version }` and `ETag` = tenant template catalogue version |
| `PUT /api/templates/{templateIntent}` | `TEMPLATE_WRITE` | `If-Match` = catalogue version | `200 OK`, new `ETag` |

`templateIntent` is validated against the closed enum; unknown values are `404`. `providerTemplateName` is validated against the provider's naming grammar (`^[a-z0-9_]{1,512}$` for Meta); the AI never sees or produces this value (ADR 0018 §4).

#### 3.3 Integration configuration and kill switch (`integration` module)

| Method and path | Permission | Headers | Success |
| --- | --- | --- | --- |
| `GET /api/integrations/whatsapp` | `INTEGRATION_READ` | | `200 OK`, `ETag`. Returns `{ enabled, outboundEnabled, phoneNumberId, businessAccountId, displayPhoneNumber, secretRef, webhookVerified, version }`. Never returns a secret value. |
| `PUT /api/integrations/whatsapp` | `INTEGRATION_MANAGE` | `If-Match` | `200 OK`, new `ETag`. Body carries identifiers, `enabled` and `secretRef` (an opaque reference the `IntegrationSecretResolver` can resolve, for example an environment-variable or secret-manager key). |
| `PUT /api/integrations/whatsapp/outbound` | `INTEGRATION_MANAGE` | `If-Match` | `200 OK`. Body `{ enabled: false }` is the **tenant kill switch**; it blocks T6 immediately and is audited as `OUTBOUND_KILL_SWITCH_CHANGED`. |

The **global kill switch** is the property `dokene.messaging.outbound.enabled` (environment `DOKENE_MESSAGING_OUTBOUND_ENABLED`, default `false`). It is read at send time, has no API, and when `false` every T6 fails with `OUTBOUND_DISABLED_GLOBALLY`. Secrets are resolved only inside the adapter at send time and are never cached in business tables, logged, included in audit metadata or returned by any endpoint.

#### 3.4 Provider webhooks (`integration` module, outside the BFF)

| Method and path | Authentication | Success |
| --- | --- | --- |
| `GET /webhooks/whatsapp` | Meta verification handshake: `hub.verify_token` compared in constant time against the configured verify token | `200 OK` with `hub.challenge` as `text/plain`; otherwise `403` |
| `POST /webhooks/whatsapp` | `X-Hub-Signature-256` HMAC-SHA-256 over the raw body with the app secret, constant-time comparison | `200 OK` empty body once the event batch is durably recorded |

Webhook handling order: verify signature on the raw bytes before parsing; validate the expected schema and reject anything else with `400`; resolve the owning tenant from the stored `phoneNumberId` in `tenant_messaging_integrations` (never from a tenant id in the payload); deduplicate on `(provider, providerEventId)` with a unique constraint in `provider_webhook_events`; establish a trusted `TenantContext` and correlation scope explicitly (`callWithTenantId`, per `docs/architecture/system-context.md`); apply the status through `DeliveryStatusSink`; append the audit event. An event whose `phoneNumberId` maps to no enabled integration, or whose provider message id matches no message, returns `200` and is counted in a privacy-safe metric (`dokene.webhook.dropped{reason}`) without storing the payload, so the endpoint reveals nothing and the provider does not retry forever. Webhook routes are not under `/api`, are served by a stateless security filter chain (no session creation, no CSRF token, no `X-Tenant-Id`), and are the only unauthenticated POST surface in the application. Processing is bounded: signature check, parse, one transaction per event, no provider calls.

### 4. Cross-cutting semantics

#### 4.1 ETags and optimistic concurrency

- Every message, template catalogue and integration read returns a **strong numeric** ETag, `"<version>"`, from the row's `version BIGINT`, exactly as customers, policies and purchases do today. Weak ETags are never emitted or accepted.
- Every mutation requires `If-Match` with that single strong value. Missing or malformed `If-Match` is `400`. A non-matching value is `409 Conflict` with code `STALE_VERSION`.
- Message creation is the one place where `If-Match` refers to a different resource: it must equal the customer's follow-up policy version returned by the evaluation, queue, recommendation or draft call. This is the server-side form of the ADR 0020 rule "never reuse a draft once the policy version changed". The stored `sourcePolicyVersion` lets the audit trail show which evaluation the operator saw.
- `If-None-Match` on `GET` returning `304 Not Modified` is permitted but not required.
- Versions advance on every committed transition, including system and webhook transitions, so an operator holding an old ETag after a delivery report gets `STALE_VERSION` rather than acting on a stale view.

#### 4.2 Idempotency

- `Idempotency-Key` follows ADR 0013: `^[A-Za-z0-9._:-]{1,128}$`, required on every `POST` that creates a record (submit, approval, cancellation, send, resolution). Missing or malformed keys are `400`.
- Scope is `(tenant_id, operation, idempotency_key)` with a unique constraint and a transaction-scoped advisory lock derived from the same tuple (ADR 0008 pattern), so concurrent first requests cannot both execute.
- Replay semantics are key-dominant: the first committed request wins. A replay with the same key, operation and target returns the original record and representation with `200 OK` and performs no re-evaluation, mutation or audit. A replay with the same key but a different target message or customer, or a different request fingerprint, returns `409 Conflict` with code `IDEMPOTENCY_KEY_REUSED`.
- Send idempotency toward the provider is separate from HTTP idempotency. Each message carries one server-generated `sendKey` (UUID) for its whole life. A send attempt row keyed by `(message_id, attempt_number)` is committed **before** the provider call. While an attempt is `STARTED` or the message has `outcomeUnknown`, no new provider call is made for that message by any path. The Meta WhatsApp Cloud API offers no client idempotency token, so exactly-once is achieved by this persist-then-call discipline plus the one-open-message-per-customer rule, not by a provider feature. If a future provider accepts an idempotency token, the adapter forwards `sendKey`.

#### 4.3 Send policy (deterministic gate for messages)

Executed in full at T6 under the customer and message locks, in this order, failing closed on the first failure with the code in parentheses:

1. active `TenantContext` and active membership (`403`);
2. `MESSAGE_SEND` held (`403`);
3. global kill switch on (`OUTBOUND_DISABLED_GLOBALLY`);
4. tenant outbound switch on and integration enabled (`OUTBOUND_DISABLED_FOR_TENANT`, `INTEGRATION_DISABLED`);
5. message state `APPROVED` with an `APPROVE` decision recorded (`INVALID_TRANSITION`);
6. customer `ACTIVE`, no do-not-contact, `contactId` still `GRANTED` for WhatsApp (`CUSTOMER_ARCHIVED`, `DO_NOT_CONTACT`, `NO_CONTACT_CONSENT`);
7. fresh follow-up evaluation is `DUE` or `OVERDUE` (`FOLLOW_UP_INELIGIBLE`);
8. no other open message for the customer, and no message `SENT`/`DELIVERED`/`READ` to the customer since the current cadence anchor (`MESSAGE_ALREADY_OPEN`, `CONTACT_FREQUENCY_EXCEEDED`);
9. template mapping for the intent exists and is enabled (`TEMPLATE_NOT_MAPPED`);
10. attempt count below `dokene.messaging.max-send-attempts` (`SEND_ATTEMPTS_EXHAUSTED`);
11. body still passes `DraftSafetyValidator` (`BODY_REJECTED`; defensive, since bodies are immutable).

Only after all eleven pass is the attempt row committed and the adapter called with a bounded timeout (`dokene.messaging.send-timeout`, default 10 s). AI output is never an input to this policy; the gate consumes stored message fields that a human submitted and approved.

#### 4.4 Error contract

Existing endpoints keep their empty-body error responses. Phase 3 endpoints need machine-readable refusal reasons, so they return `application/problem+json` (RFC 9457) with exactly these members and nothing else:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409, "code": "STALE_VERSION" }
```

- `code` is a closed enum (`MessagingErrorCode`), mirrored in the frontend as a string union. No `detail`, no free text, no provider text, no identifiers. The correlation id is still only in `X-Request-Id`.
- Status mapping: `400` invalid input, missing or malformed required headers, unknown enum values (`INVALID_INPUT`); `401` no or expired session (BFF, empty body as today); `403` tenant, membership, permission or webhook signature failures (`FORBIDDEN`, generic, audited per ADR 0005); `404` message, customer, contact or template intent not visible in the tenant (`NOT_FOUND`); `409` every policy and concurrency refusal listed in §4.2 and §4.3 plus `INVALID_TRANSITION`; `429` send rate limit per tenant with `Retry-After` (`RATE_LIMITED`); `503` audit persistence failure (empty body, ADR 0006) and provider unavailable when the failure happened before any attempt row existed (`PROVIDER_UNAVAILABLE`).
- A provider failure that happens **after** the attempt row is committed is never an HTTP error: it is a `201` whose body shows `status` and `lastAttempt.failureCategory` (T9 to T11), because the state change is real and must be visible.
- The frontend `getFriendlyErrorMessage` keeps its status-based fallbacks; the `messages` feature maps `code` to Latin American Spanish copy.

#### 4.5 Audit

New closed event types in the `audit` module, each with metadata limited to constrained columns: target `MESSAGE` UUID, `status_from`, `status_to`, optional `failure_category`, optional `attempt_number`; integration and template events carry the target `INTEGRATION` or `TEMPLATE_MAPPING` UUID and a boolean `enabled`. Bodies, phone numbers, provider message ids, notes, provider payloads and secret references are never audit metadata. Webhook-driven events are attributed to the tenant with no actor and no membership (actor kind `PROVIDER`), using the explicit trusted context the ingestion boundary establishes; this is a new permitted attribution shape and requires a migration that extends `ck_audit_shape` and `dokene.append_audit_event` in the same additive style as V13 to V15. Business transitions commit atomically with their audit row (`MESSAGE_SUBMITTED`, `MESSAGE_APPROVED`, `MESSAGE_REJECTED`, `MESSAGE_CANCELLED`, `MESSAGE_SEND_REQUESTED`, `MESSAGE_SENT`, `MESSAGE_SEND_FAILED`, `MESSAGE_SEND_OUTCOME_UNKNOWN`, `MESSAGE_DELIVERY_UPDATED`, `TEMPLATE_MAPPING_UPDATED`, `INTEGRATION_UPDATED`, `OUTBOUND_KILL_SWITCH_CHANGED`); denied policy checks at T1, T2 and T6 are audited as `AUTHORIZATION_DENIED` with the relevant permission only when the failure is an authorization failure, and otherwise are not audited (they are refusals of valid requests, visible through metrics).

#### 4.6 Follow-up cadence linkage (amends ADR 0013)

A message reaching `SENT` records `last_outbound_message_date = tenant-local date of sentAt` on the customer follow-up policy through a `followup.application` port owned by `followup` (`FollowUpTouchRecorder`). Anchor precedence becomes the latest of `last_manual_follow_up_date`, `last_dismissed_date`, `last_outbound_message_date` and the latest valid purchase date. Active snoozes and explicit next dates are cleared, as dismissals and manual completions do today. `DELIVERED` and `READ` do not touch cadence. This is what makes a sent message count as a completed follow-up so the customer leaves the due queue.

#### 4.7 Persistence

New tenant-scoped tables, each following `docs/architecture/tenant-isolation-rls-recipe.md` (`tenant_id`, FK to `dokene.tenants`, forced RLS with `dokene.current_verified_tenant_id()`, runtime DML grants only): `outbound_messages`, `outbound_message_approvals`, `outbound_message_cancellations`, `outbound_send_attempts`, `outbound_message_events` (append-only, no runtime UPDATE/DELETE), `outbound_message_idempotency_keys`, `tenant_messaging_integrations`, `tenant_provider_templates`, `provider_webhook_events`. Partial unique index `one_open_message_per_customer ON outbound_messages (tenant_id, customer_id) WHERE status NOT IN ('READ','REJECTED','CANCELLED','FAILED')` enforces the open-message rule at the database. `customer_follow_up_policies` gains `last_outbound_message_date DATE NULL`. Bodies are stored as `VARCHAR(1000)` in `outbound_messages` only; they are tenant business data, not audit data.

### 5. Permission matrix

The role-to-permission map in `TenantRolePermissions` is **not modified** by this ADR. Every Phase 3 operation maps onto an existing permission, and the table below is derived from the current map.

| Operation | Permission required | OWNER | ADMIN | OPERATOR | VIEWER |
| --- | --- | --- | --- | --- | --- |
| List and read messages, read message events | `MESSAGE_READ` | ✔ | ✔ | ✔ | ✔ |
| Generate an AI draft (existing, ADR 0018) | `MESSAGE_DRAFT` + `FOLLOWUP_EVALUATE` | ✔ | ✔ | ✔ | ✘ |
| Submit a message for approval (T1) | `MESSAGE_DRAFT` | ✔ | ✔ | ✔ | ✘ |
| Cancel a pending message (T4) | `MESSAGE_DRAFT` | ✔ | ✔ | ✔ | ✘ |
| Approve or reject (T2, T3) | `MESSAGE_APPROVE` | ✔ | ✔ | ✔ | ✘ |
| Cancel an approved message (T5) | `MESSAGE_APPROVE` | ✔ | ✔ | ✔ | ✘ |
| Request a send (T6) | `MESSAGE_SEND` | ✔ | ✔ | ✔ | ✘ |
| Resolve an unknown send outcome as failed (T12) | `INTEGRATION_MANAGE` | ✔ | ✔ | ✘ | ✘ |
| Read template mappings | `TEMPLATE_READ` | ✔ | ✔ | ✔ | ✔ |
| Edit template mappings | `TEMPLATE_WRITE` | ✔ | ✔ | ✔ | ✘ |
| Read integration configuration (no secrets) | `INTEGRATION_READ` | ✔ | ✔ | ✔ | ✔ |
| Edit integration configuration, secret reference | `INTEGRATION_MANAGE` | ✔ | ✔ | ✘ | ✘ |
| Tenant outbound kill switch | `INTEGRATION_MANAGE` | ✔ | ✔ | ✘ | ✘ |
| Global outbound kill switch | Deployment configuration only | — | — | — | — |
| Provider webhook ingestion | No tenant actor; signature-authenticated system path | — | — | — | — |
| Read message audit history | `AUDIT_READ` (existing) | ✔ | ✔ | ✘ | ✘ |

Consequences of preserving the map: an `OPERATOR` can take a message from draft to sent alone, and can edit template mappings. Tenants that want a two-person rule or admin-only template editing need the Phase 5 policy work, and any change to the role map needs its own ADR with a migration plan for existing memberships and QA seeds.

### 6. Backend for Frontend preservation

- No new cookie, token or header reaches the browser. All Phase 3 browser traffic is same-origin `fetch` to `/api/**` with `JSESSIONID`, `X-CSRF-TOKEN`, `X-Tenant-Id`, `If-Match` and `Idempotency-Key`, which are already in the CORS allow-list; `ETag` and `X-Request-Id` remain the only exposed response headers.
- Provider credentials, the app secret, the verify token and provider message payloads exist only inside `integration` on the server. The frontend never calls Meta.
- The webhook chain is a separate `SecurityFilterChain` ordered before the session chain, matching `/webhooks/**` only, with `SessionCreationPolicy.STATELESS`, CSRF disabled for that matcher only, and no OAuth2 login entry point, so it cannot create sessions, cannot be reached with a session cookie as proof of anything, and cannot redirect to the provider login page.
- The nginx and Vite proxies (ADR 0022) add `/webhooks` to the proxied prefixes; in production the edge exposes `/webhooks/whatsapp` to Meta's published IP ranges or through the same ingress with the signature check as the trust decision.
- `GET /api/session`, `/logout`, tenant selection and 401/403 semantics are unchanged. Hiding controls in the UI for roles without a permission remains a usability aid, not a boundary.

### 7. Configuration summary

| Property | Default | Purpose |
| --- | --- | --- |
| `dokene.messaging.outbound.enabled` | `false` | Global kill switch |
| `dokene.messaging.max-send-attempts` | `3` | Upper bound for T10 before `FAILED` |
| `dokene.messaging.send-timeout` | `10s` | Provider call timeout per attempt |
| `dokene.messaging.send-rate-limit.per-tenant-per-minute` | `30` | `429` with `Retry-After` on T6 |
| `dokene.integration.whatsapp.verify-token-ref` | none | Secret reference for the webhook handshake |
| `dokene.integration.whatsapp.app-secret-ref` | none | Secret reference for signature verification |

Secret references resolve through `IntegrationSecretResolver`; the Phase 3 implementation is environment-backed and a secret-manager implementation is a later adapter behind the same port.

## Alternatives considered

- **Keep a server-side `DRAFT` state** (as the wiki sketched). Rejected: it would duplicate the client-side advisory draft of ADR 0020, require edit endpoints, and let an approval drift from the text it approved. Immutable submission is simpler and safer.
- **One `PATCH /api/messages/{id}` with a `status` field.** Rejected: it invites arbitrary status mutation, contradicts invariant 9, and hides which permission governs which transition. Separate command resources keep permission, idempotency and audit per transition.
- **Put the Meta adapter in `messaging.provider`** (mirroring `ai.provider`). Rejected: webhooks, credentials and tenant integration configuration belong together, and they are not message lifecycle concerns. `integration` already exists in the architecture documents for exactly this.
- **Make `followup` depend on `messaging`** (wiki sketch). Rejected: `messaging` needs follow-up evaluation at submit and send time, so this direction would create a cycle. The cadence-advance port lives in `followup` and is called by `messaging`.
- **Asynchronous send with `202 Accepted` and a queue.** Deferred to Phase 4, which introduces the scheduler and multi-instance claiming. The `QUEUED` and `SENDING` states and the attempt rows are designed so the Phase 4 dispatcher consumes them without a schema redesign.
- **Change role mappings** (admin-only template editing, two-person approval). Rejected for Phase 3 by requirement: existing permissions are preserved. Recorded as Phase 5 policy work.
- **Reuse `412 Precondition Failed` for stale ETags.** Rejected: every existing endpoint uses `409`, and changing conventions mid-product costs more than it buys.
- **Free-text `detail` in error bodies.** Rejected: free text is where provider messages, phone numbers and notes leak; a closed `code` enum is enough for the UI and safe for logs.

## Consequences

- Phase 3 can be built as three new modules plus one small change in `followup` and additive audit migrations, with the dependency direction checkable by an architecture test.
- An approved message is sent at most once: the attempt row is committed before the provider call, unknown outcomes freeze the message, and the one-open-message index blocks a second message to the same customer. The cost is that an unknown outcome needs a human (T12) or the Phase 4 reconciliation job to clear.
- Operators get stable, machine-readable refusal reasons for the first time; older endpoints keep empty bodies until a separate decision migrates them.
- `OPERATOR` keeps end-to-end send capability. Tenants needing stricter control must wait for Phase 5 policies.
- The BFF contract is unchanged; the only new unauthenticated surface is the signature-verified webhook chain.
- Documentation to update when this ADR is accepted and implemented: `docs/security/security-invariants.md` (new invariants: outbound messages are immutable after submission and sent at most once through a persist-then-call attempt; webhooks are signature-verified, deduplicated and tenant-resolved from stored configuration only; message bodies, phone numbers and provider payloads never enter audit, logs or metrics), `docs/architecture/system-context.md` (messaging and integration boundaries), `docs/wiki/Messaging-and-Integrations.md` and `docs/wiki/Architecture.md` (retire `DRAFT`, fix the `followup → messaging` arrow), ADR 0013 (anchor precedence) and ADR 0020 (the "Enviar a aprobación" control).

## Out of scope

Scheduler-driven sending, multi-instance work claiming, automatic retries, reconciliation jobs, provider health dashboards, inbound customer replies, additional channels, template submission to Meta for approval, auto-send policies, and any change to `TenantRolePermissions`.
