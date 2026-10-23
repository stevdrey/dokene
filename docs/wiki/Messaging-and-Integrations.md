# Messaging and Integrations

## Messaging goal

Messaging is an external side effect and must be treated as a controlled boundary.

The initial channel is WhatsApp, but the domain should not be coupled directly to one provider SDK or one transport.

## Provider abstraction

Core application logic should depend on a narrow contract such as:

```text
MessagingProvider
```

The initial adapter, `GraphApiMessagingProvider` ([ADR 0027](../adr/0027-meta-whatsapp-send-adapter.md)), sends approved templates through the Meta WhatsApp Cloud API (Graph API v23.0, available until 2027-10-08). It is a transport only: one bounded `POST` to the allow-listed Graph host with the tenant's own credential, and no retry. Future adapters may target other providers or channels without changing follow-up policy.

The provider abstraction should expose application-level semantics, not leak arbitrary provider JSON into the domain.

### Frozen Phase 3 contracts

[ADR 0024](../adr/0024-phase-3-messaging-contract-freeze.md) freezes the v1 messaging contract that both Phase 3 tracks build against:

- HTTP records in `messaging.api.MessagingApiContract`, mirrored by `frontend/src/features/messages/types`;
- synthetic fixtures in `backend/src/test/resources/contracts/messaging/v1/`, checked by `MessagingContractFixturesTest` (backend) and `contractFixtures.test.ts` (frontend), so an incompatible change fails both workflows;
- ports with fail-closed rules: `OutboundSafetyControl` (kill switches, integration enabled, sender identity), `TemplateMappingResolver`, `IntegrationSecretResolver` (secrets never leave `integration`) and `VerifiedWebhookEvent`;
- the approval fingerprint (`approval/v3`): an approval binds recipient, content, template mapping revision and its verified parameter format, exact parameter values, sending identity and consent revision; if any of them changes before send, the send is refused with `APPROVAL_INVALIDATED` and the operator cancels and resubmits.

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

`READ`, `REJECTED`, `CANCELLED` and `FAILED` are terminal. The states before the provider accepts a message (`PENDING_APPROVAL`, `APPROVED`, `QUEUED` and `SENDING`, an unknown outcome included) count as open, and a partial unique index (`one_open_message_per_customer`) keeps at most one open message per customer per tenant. `SENT` and `DELIVERED` are neither terminal nor open: they still take delivery reports, but a new message to the customer is allowed, because those reports may never arrive ([#198](https://github.com/stevdrey/dokene/issues/198)). The follow up cadence and the minimum contact interval still space messages out.

Transitions are validated in the aggregate; arbitrary status mutation is refused with `INVALID_TRANSITION`. Each applied transition advances the message version by one and appends one row to the event log, so the latest event sequence number always equals the message version.

### Review and approval API

The review routes ([#136](https://github.com/stevdrey/dokene/issues/136)) wrap the T1 to T5 services with the frozen v1 records (`MessageController`, `MessagingExceptionHandler`):

| Route | Permission | Notes |
| --- | --- | --- |
| `POST /api/customers/{customerId}/messages` | `MESSAGE_DRAFT` | Submit (T1). `If-Match` is the customer's follow-up policy version, `Idempotency-Key` required. `201` with `Location`, replay `200`. |
| `GET /api/messages` | `MESSAGE_READ` | `status` (repeatable), `customerId`, `cursor`, `limit` 1 to 100. Items carry no body or phone. |
| `GET /api/messages/{messageId}` and `/events` | `MESSAGE_READ` | Detail with strong `ETag`; append-only history without membership ids. |
| `POST /api/messages/{messageId}/approvals` | `MESSAGE_APPROVE` | `{ decision: APPROVE \| REJECT, note? }` (T2, T3). Approval calls no provider. |
| `POST /api/messages/{messageId}/cancellations` | `MESSAGE_DRAFT` or `MESSAGE_APPROVE` | `{ note? }` (T4, T5). Either permission passes the route; the service then requires `MESSAGE_DRAFT` for a pending message and `MESSAGE_APPROVE` for an approved one. |

Rules that hold for every route:

- **No edit route.** Body, recipient, intent and locale are immutable once submitted. To change the text, cancel and submit a new message; the new message needs its own approval.
- **Strict bodies.** Unknown members, unknown enum values, numbers for enums or text, and missing required members are `400 INVALID_INPUT`, so a client cannot set a status, a recipient or a fingerprint.
- **Authorization first.** Permissions are checked before headers or bodies are read, so a `VIEWER` gets `403` even for a malformed request. Another tenant's ids are `404`.
- **Preconditions.** `If-Match` must be one strong numeric ETag (`"3"`); a stale one is `409 STALE_VERSION`. Replays follow ADR 0023 §4.2; a reused key with another payload or target is `409 IDEMPOTENCY_KEY_REUSED`.
- **Responses.** Every command answers with the current detail representation and its `ETag`. Message responses are `Cache-Control: no-store` because `contactRemoved` is derived live. Refusals are closed-code `application/problem+json`; an audit failure is `503` with an empty body and rolls the change back. A database outage is an empty `503` with `Retry-After` (ADR 0029).

Approval (T2) re-checks customer status, do-not-contact, WhatsApp consent, eligibility, the body, the template mapping and the open-message rule from stored state under the customer lock. It then computes the approval fingerprint and stores it on the approval row (V22). The fingerprint is never returned, audited or logged. Template parameters are rendered from a closed vocabulary only:

| Parameter name | Value |
| --- | --- |
| `customer_name` | The customer's current display name |
| `message_body` | The submitted body, unchanged |

A mapping with any other parameter name, or written in a different language from the message locale (`es`/`es_MX` for `es-419`), is refused with `TEMPLATE_NOT_MAPPED`. A rendered value the provider would reject (a line break, a run of five spaces) is refused with `BODY_REJECTED`. A tenant without a configured integration is refused with `INTEGRATION_DISABLED`; the kill switches are not checked at approval.

### Tables (V18)

| Table | Purpose | Runtime grants |
| --- | --- | --- |
| `outbound_messages` | One row per message: status, body, recipient snapshot (`contact_id` plus `recipient_phone`, with no foreign key to the contact row so phone corrections still work; readers derive `contactRemoved` from whether the contact row still exists), send key, attempt count, provider message id, timestamps, version. | `SELECT, INSERT, UPDATE` |
| `outbound_message_approvals` | One row per approve or reject decision, with the operator note and, for an approval, its fingerprint (V22). | `SELECT, INSERT` |
| `outbound_message_cancellations` | One row per cancellation, with the operator note. | `SELECT, INSERT` |
| `outbound_send_attempts` | One row per provider call, committed before the call and completed after it. | `SELECT, INSERT, UPDATE` |
| `outbound_message_events` | Append only log of every transition and every ignored delivery report. | `SELECT, INSERT` |
| `outbound_message_idempotency_keys` | `(tenant, operation, key)` with a request fingerprint, so a replay returns the first result and a reuse with a different payload is refused. | `SELECT, INSERT` |

All six tables carry forced row level security with the standard five policies, and the runtime role never holds `DELETE`. Operator notes stay in the approval and cancellation rows and never reach the audit log or application logs.

V19 extends the audit log with `MESSAGE_*` event types and closed transition metadata (status from, status to, failure category, attempt number). Delivery reports are audited with tenant only attribution under a provider scoped signed context; no member is credited for a provider callback.

## Idempotent sending

External sends must remain safe under:

- scheduler retries;
- HTTP retries;
- process crashes;
- webhook duplication;
- network timeouts where send outcome is initially ambiguous;
- multiple application instances.

A logical message/follow-up should have a durable idempotency identity so retrying the operation does not accidentally contact the customer twice.

Manual dispatch (`POST /api/messages/{messageId}/sends`, `MESSAGE_SEND`, `Idempotency-Key` and strong `If-Match` required, no body; [ADR 0028](../adr/0028-manual-dispatch-claim-and-last-moment-send-gate.md), issue 138) works like this:

1. **Claim.** One transaction runs the full last-moment send policy under the customer and message locks: kill switches, integration, consent, do-not-contact, archive, cadence, contact frequency, tenant rate, re-resolved template and approval fingerprint. In the same transaction it applies T6 and T7, commits a `STARTED` attempt row naming the authorizing member, and records the idempotency key. That row is the durable claim; a partial unique index allows only one per message.
2. **Call.** The provider is called once, outside any transaction.
3. **Record.** A second transaction records the outcome (T8 to T11) and its audit row.

What each case produces:

- A refusal before the claim writes nothing and makes no provider call. That includes a membership that was revoked or demoted after the request began, because the claim re-reads it, and any request body.
- The same key again replays the current state (`200`). The same key for another message or version is `409 IDEMPOTENCY_KEY_REUSED`.
- A concurrent click with another key loses the lock race (`STALE_VERSION` or `INVALID_TRANSITION`).
- If the outcome cannot be recorded, for example during an audit outage, the claim stays and the message stays `SENDING`: sending halts instead of risking a duplicate.
- **Recovery.** A scheduled processor finds claims still `STARTED` after `dokene.messaging.recovery.claim-timeout` and records them as unknown outcomes (T11) for a human to resolve (T12). It never calls the provider. It records as the member who authorized the send, and only while that membership is still active and still holds `MESSAGE_SEND`, checked again inside the transaction; otherwise, or if the outcome still cannot be recorded, it leaves the claim in place and logs ids only.

Recovery settings:

| Property | Default | Meaning |
| --- | --- | --- |
| `dokene.messaging.recovery.enabled` | `true` | Runs the scheduled processor; only one instance runs it at a time. Every Gradle test task sets it to `false` and drives the processor directly. |
| `dokene.messaging.recovery.claim-timeout` | 10m | Age after which a `STARTED` claim counts as abandoned. Must be at least `dokene.messaging.send-timeout` (10s) plus 1m, or startup fails while recovery is enabled. |
| `dokene.messaging.recovery.batch-size` | 50 | Claims handled per run (1 to 500). Runs page through the expired claims by attempt id and wrap around, so claims that must be left alone never block the others. |
| `dokene.messaging.recovery.interval` | 1m | Delay between runs. |

Send policy bounds:

| Property | Default | Refusal |
| --- | --- | --- |
| `dokene.messaging.max-send-attempts` | 3 | `SEND_ATTEMPTS_EXHAUSTED` |
| `dokene.messaging.min-contact-interval` | 24h | `CONTACT_FREQUENCY_EXCEEDED` |
| `dokene.messaging.send-rate-per-tenant-per-minute` | 30 | `429 RATE_LIMITED` with `Retry-After` |
| `dokene.messaging.send-timeout` | 10s | Past the deadline the provider call is interrupted and its outcome is unknown |
| `dokene.messaging.outbound.enabled` | false | `OUTBOUND_DISABLED_GLOBALLY` for every tenant while off; bound once at startup, so a change needs a restart (spec 0008) |

A disable or revocation that commits after the claim cannot retract a call already in flight; it stops later sends only. Application duplicate suppression is not proof of exactly-once delivery by the provider. Live sending is off by default: the global switch `dokene.messaging.outbound.enabled` and each tenant's switch must both be on (see [Disabling and re enabling outbound sending](#disabling-and-re-enabling-outbound-sending)); the template schema check (#218) is in place.

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

The template registry ([ADR 0025](../adr/0025-whatsapp-template-registry-and-dispatch-policy.md)) stores one mapping per semantic intent and tenant: provider template name, language, ordered parameter names and an `enabled` flag, behind `GET /api/templates`, `PUT /api/templates/{templateIntent}` and `POST /api/templates/{templateIntent}/approval-refresh`. Approval is provider evidence, never operator input: the refresh asks Meta's Graph API with the tenant's own credential and records the status, check time, business account and mapping revision. A mapping is sendable only when it is enabled, `APPROVED` for its current revision and business account, and the evidence is younger than `dokene.template.approval-max-age` (24 hours by default). Editing a mapping drops the evidence and voids open approvals; a failed refresh drops it too. Phase 3 never sends free-form text: with no trusted customer-service-window evidence it sends only a verified template. The Graph API host is allow-listed (`graph.facebook.com`, loopback test servers, or hosts explicitly listed in `dokene.integration.whatsapp.graph.additional-trusted-hosts`) because tenant credentials are sent to it. Meta's `APPROVED` is recorded only when the template's component schema matches the mapping (#218): text parameters in the body only, positional `{{1}}..{{n}}` matching the number of parameter names or named placeholders matching the stored names. Otherwise the refresh stores `SCHEMA_MISMATCH`, `SCHEMA_UNSUPPORTED` or `SCHEMA_UNREADABLE`, which are never sendable and expose no provider text. Approvals recorded before this check were reset to `UNVERIFIED` and must be refreshed; the migration is expand-only, and an approval rewritten by an instance of the previous release during a rolling deploy reads as unchecked and stays not sendable until refreshed again. Both formats are sendable: the approval binds the verified format, so a template re-verified under another format voids open approvals, and a named template is sent with each value's `parameter_name`. Since #268 an approved refresh also stores the template's fixed header, body and footer text and its quick-reply, phone and static URL buttons, returned as `content` and bound into the approval: a text or button change at Meta followed by a refresh voids open approvals. Approvals recorded before that change were reset to `UNVERIFIED` once more. When a mapping has no `message_body` parameter, the message body must be exactly the template's approved body text, otherwise `BODY_REJECTED`.

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

### Meta signature and handshake verification (spec 0007, issue 143)

Step 1 is implemented by the `integration.whatsapp.webhook.verify` package. The route, tenant resolution, inbox and dedup (issue 139) compose it: read the body through `BoundedWebhookBodyReader`, pass the raw bytes and every `X-Hub-Signature-256` value to `WebhookSignatureVerifier`, and hand the resulting `VerifiedWebhookBody` to the parser. Nothing parses JSON before verification.

**Meta's rules as checked on 2026-10-19** (Graph API webhooks getting started page, the Messenger Platform webhooks page, the WhatsApp Cloud API set up page and the Graph API versions changelog; current Graph API version v26.0, released 29 July 2026; no webhook page states a version and the Messenger page says to use the latest):

- Signature: header `X-Hub-Signature-256`, value `sha256=` followed by the hex HMAC SHA-256 of the payload keyed with the App Secret. The digest is computed over the escaped unicode payload exactly as Meta sends it, so a decoded or reserialised body gives a different signature; Dokene therefore verifies the exact request bytes and never a reparsed object. The verifier accepts hex in either case (the Messenger page states lowercase; no WhatsApp page states the case), requires exactly one header value and exactly 64 ASCII hex digits, trims nothing, and never receives the legacy SHA-1 `X-Hub-Signature` header.
- Handshake: `GET` with `hub.mode` (always `subscribe`), `hub.verify_token` and `hub.challenge`; the expected answer is the challenge value with `200`, and `403` on a mismatch. Meta describes the challenge as an integer and documents no length limit; Dokene accepts up to 256 printable ASCII characters so a format change does not silently break the subscription, and echoes only the validated input.
- Payload size: up to 3 MB per the WhatsApp Cloud API set up page (generic webhook text, unconfirmed for WhatsApp specifically); batches of up to 1000 updates. Meta retries a non `200` answer for up to 7 days (WhatsApp changelog of 8 December 2022) or 36 hours (Graph API page); the sources conflict, so the bound keeps headroom above Meta's limit rather than refusing a large legitimate batch for days.
- Not confirmed on an official page: a WhatsApp specific statement of the signature rule, the hex case for WhatsApp, and any `hub.challenge` length limit.

**Configuration.** `dokene.integration.whatsapp.app-secret-ref` (`DOKENE_INTEGRATION_WHATSAPP_APP_SECRET_REF`) and `dokene.integration.whatsapp.verify-token-ref` (`DOKENE_INTEGRATION_WHATSAPP_VERIFY_TOKEN_REF`) hold the bare keys of the `APP_SECRET` and `WEBHOOK_VERIFY_TOKEN` deployment entries of the secret source above, never the secrets. Each is resolved on every call and closed before the call returns. A blank reference puts that path in the refusing state: the stack boots, logs `Webhook verification refusing: <property> is unset` once, and refuses every request on that path with `SECRET_UNAVAILABLE`; the two paths are independent. A malformed or whitespace padded reference fails startup naming only the property. `dokene.integration.whatsapp.webhook.max-body-bytes` (`DOKENE_INTEGRATION_WHATSAPP_WEBHOOK_MAX_BODY_BYTES`, default 4194304, range 1 to 16777216) bounds the body; a declared `Content-Length` above it is refused before a byte is read, and a stream is refused as soon as it passes the bound.

**Refusals.** Every refusal is `WebhookVerificationException` with one closed reason (`MISSING_SIGNATURE`, `MALFORMED_SIGNATURE`, `SIGNATURE_MISMATCH`, `BODY_TOO_LARGE`, `SECRET_UNAVAILABLE`, `WRONG_MODE`, `INVALID_CHALLENGE`, `TOKEN_MISMATCH`), one `WARN` line `Webhook rejected reason=<R>` from the refusing class, and one increment of the counter `dokene.webhook.rejected{reason}`. No secret, header value, challenge or body byte reaches a log, an exception or a `toString`. The route answers every reason with the same status, so an operator diagnosing a misconfigured secret reads the counter or the log line.

### Webhook route, inbox and delivery reconciliation (issue 139, ADR 0030)

`GET /webhooks/whatsapp` answers Meta's subscription handshake. `POST /webhooks/whatsapp` takes delivery callbacks.

**Filter chain.** Both run on their own `SecurityFilterChain`, which is:
- stateless;
- without session, login or tenant filter, with CSRF exempting only the exact callback path;
- ordered before the BFF chain.

Every other path under `/webhooks/` is `403`. The nginx and Vite proxies forward `/webhooks`; nginx caps the body at 4 MiB.

**Request handling:**
1. The body is read through the bounded reader.
2. The HMAC is checked on those raw bytes. Any refusal is `403`.
3. `MetaWebhookPayloadParser` checks every member against an allowlist per level. A supplied `tenantId` or any other unknown member is `400`, before any tenant lookup. Inbound messages, other change fields and untracked statuses are only counted.
4. Each delivery status is resolved to a tenant through the verified mapping resolver only.
5. Each tenant's statuses are stored as typed `PENDING` rows in `provider_webhook_events`, in one transaction under that tenant's RLS.
6. Then the route answers `200` with an empty body.

A database or audit failure while recording answers `503` (ADR 0029), and Meta retries.

**Deduplication.** The key is `hex(SHA-256("META_WHATSAPP|" + tenantId + "|" + providerMessageId + "|" + status))`, unique per provider. A replay changes nothing and writes no audit row.

**Applying rows.** Rows are applied under a provider context with no member, in one transaction with the transition and its audit:
- **Applied:** the row becomes `APPLIED`.
- **Ignored:** an out of order or late report is recorded as a non applied event and the row becomes `IGNORED`. A late failure never erases a delivery.
- **Not found:** the row stays `PENDING` and is retried with backoff for the park window. This covers a report that beat the dispatch commit. After the window the row becomes `UNMATCHED` with its provider message id cleared.

A row that keeps failing past the abandon window becomes `ABANDONED` with its provider message id cleared.

The request applies up to the inline limit of what it stored. A scheduled sweeper (single instance via an advisory lock) resumes the rest, including after a restart or an audit outage, and purges terminal rows after the retention period.

**Counters.** `dokene.webhook.dropped{reason}` counts acknowledged events that changed nothing: `UNKNOWN_PHONE_NUMBER`, `DUPLICATE`, `UNMATCHED_MESSAGE`, `UNSUPPORTED_EVENT` and `PROCESSING_ABANDONED`.

**Privacy.** The inbox never holds raw JSON, error texts, recipient phones, conversation or pricing data. Delivery history stays `GET /api/messages/{messageId}/events`, where webhook transitions appear as `DELIVERY_UPDATED` events with `actorKind=PROVIDER`.

| Setting (`dokene.integration.whatsapp.webhook.inbox.*`) | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Runs the sweeper. Inline processing always runs. |
| `interval` | `PT30S` | Delay between sweeper runs. |
| `park-window` | `PT15M` | How long a report whose message is not found is retried. Allowed range: 0 to 1 hour. |
| `abandon-after` | `P1D` | How long after receipt a row that keeps failing is abandoned. At least 1 hour and the park window. |
| `retention` | `P30D` | How long processed rows are kept for deduplication. At least 8 days and `abandon-after`. |
| `batch-size` | `100` | Due rows per run. Allowed range: 1 to 500. |
| `inline-limit` | `50` | New rows a request applies before answering. Allowed range: 0 to 1000. |

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

### WhatsApp integration API (V20, issue 134)

`tenant_messaging_integrations` holds one `META_WHATSAPP` record per tenant under forced row level security (runtime grants `SELECT, INSERT, UPDATE`). The Meta `phone_number_id` and `business_account_id` are numeric identifiers and are stored apart from the optional human `display_phone_number` (E.164). `(provider, phone_number_id)` is unique among **verified** mappings only (V24, issue 210): several tenants may hold an unverified claim of one number, but only the tenant that proves ownership is ever routed to or sends from it.

| Route | Permission | Behaviour |
| --- | --- | --- |
| `GET /api/integrations/whatsapp` | `INTEGRATION_READ` (all roles) | Returns `enabled`, `outboundEnabled`, the identifiers, `displayPhoneNumber`, `secretRef`, `webhookVerified` and `version` with a strong `ETag`; `404 NOT_FOUND` when none exists. `outboundEnabled` is the stored tenant kill switch (spec 0008), off for every new integration. `webhookVerified` is the stored ownership proof for the current number, business account and credential revision. |
| `PUT /api/integrations/whatsapp` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | Exactly one precondition is required, else `400`: `If-None-Match: *` creates the record (`409 STALE_VERSION` if one already exists) and `If-Match: "<version>"` replaces it (stale `409 STALE_VERSION`). Any well-formed `phoneNumberId` is accepted and stored unverified; a `PUT` is never throttled. Changing `phoneNumberId`, `businessAccountId` or `secretRef`, or disabling the integration, clears the ownership proof; re-enabling does not restore it. |
| `POST /api/integrations/whatsapp/credential-rotations` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | Records that the secret behind the unchanged `secretRef` was replaced in the store. Strong `If-Match`, no body; bumps `credential_revision` and the version, clears the ownership proof and is audited; `404` when no integration exists. |
| `PUT /api/integrations/whatsapp/outbound` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | The tenant outbound kill switch (spec 0008). Strong `If-Match` and a body of exactly `{ "enabled": true }` or `{ "enabled": false }`; anything else in the body (`"true"`, `1`, `null`, an extra member) is `400 INVALID_INPUT`. A real flip bumps the version and appends one `OUTBOUND_KILL_SWITCH_CHANGED` audit row with the new value; stating the stored value is a `200` no op with the unchanged `ETag` and no audit row. `409 STALE_VERSION`, `404` when no integration exists. Independent of `enabled` and of the proof: an admin can switch on while the integration is disabled or unproven, and the configuration `PUT`, a rotation and a proof never change it. The global switch has no route. |
| `POST /api/integrations/whatsapp/ownership-verifications` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | Proves at Meta, with the tenant's own credential, that the stored business account lists the stored phone number, and records the proof (`webhookVerified=true`). Strong `If-Match`, no body; bumps the version and is audited. `400 INVALID_INPUT` when not proven or when another tenant already verified the number (indistinguishable), `400 INVALID_INPUT` for a disabled integration (no Meta call, no budget), `503 PROVIDER_UNAVAILABLE` when Meta gives no conclusion or the tenant's token is invalid or expired (`401`), `409 STALE_VERSION`, `404`, and `429 RATE_LIMITED` while the rejected-proof budget is spent. |

- Tenant-selection failures are rejected by `TenantContextRequestFilter` before the controller, with unchanged statuses and audit, and use the closed problem+json shape on every Phase 3 route (`/api/messages/**`, `/api/customers/{customerId}/messages`, `/api/integrations/**`, `/api/templates/**`): a missing `X-Tenant-Id` or no membership is `403 FORBIDDEN`, a duplicated or malformed one is `400 INVALID_INPUT`. Each module claims its own routes through a `TenantSelectionRejectionResponder` (ADR 0024, issue 207 addendum), and each route has exactly one owner. A route several responders claim gets the empty-body error. It is logged with the responder classes and a normalized method, never the request URI (issue 222); other endpoints keep the empty-body `400`/`403`.
- Tenant and actor come from the verified context, never the body. Unknown or wrongly typed members, including any raw token, are rejected with `400 INVALID_INPUT`.
- The body carries a `secretRef`, a bare key (`^[A-Za-z0-9_-]{1,128}$`). Only the reference is stored: it is resolved by `IntegrationSecretResolver` inside the provider adapter, at send time or for an explicit ownership verification (fail closed: unknown, missing or wrong-scope references block the operation), never when configuration is written, so the write path needs no resolver. Secret values are never stored, returned, logged or audited, and writing configuration never contacts Meta.
- Ownership verification (issue 210):
  - `PUT` never conflicts on the number, so naming another tenant's number neither blocks it nor reveals that it is in use.
  - A proof that Meta confirms but that another tenant's verified mapping blocks is answered exactly like an unproven number. That is the only remaining availability signal, and it is throttled (issue 208): each `400` refusal consumes a budget per tenant and per actor in a sliding window, configured by `dokene.integration.whatsapp.mapping-rejection-limit` (`tenant-rejections` 10, `actor-rejections` 5, `window` 15m).
  - `503 PROVIDER_UNAVAILABLE`, stale preconditions, successful proofs and configuration writes consume nothing. Each proof reserves a unit before calling Meta, so parallel attempts cannot exceed the budget.
  - While either budget is spent, every proof gets `429` with the closed `RATE_LIMITED` problem and `Retry-After`, and Meta is not called. Configuration writes and credential rotations are never throttled, so disabling always works.
  - Every refused proof appends one `AUTHORIZATION_DENIED` (`INTEGRATION_MANAGE`) in its own transaction: reason `MAPPING_REJECTED` for each `400` and `RATE_LIMITED` for each `429`. No candidate id appears in responses, logs or audit rows.
  - The budget lives in memory per instance; the remaining risk is tracked in the threat model.
  - Until a production secret resolver exists (#142), every proof fails closed with `503 PROVIDER_UNAVAILABLE`.
- Rotating a secret in place (same `secretRef`): replace it in the store, then `POST /api/integrations/whatsapp/credential-rotations` with `If-Match` (no body). Changing `secretRef` bumps `credential_revision` too, which is part of the approval sender identity: open approvals then fail the send with `APPROVAL_INVALIDATED`. Enabling or disabling does not void approvals; the enabled flag is read live at send time.
- The `ETag` is the configuration concurrency token (the stored `version`); recording or clearing a proof and flipping the tenant switch bump it. The row can still change behind a client between reads, so `GET` and `PUT` send `Cache-Control: no-store` and never answer `304`.
- **Reclaiming a pre-registered number.** If another tenant mapped your number first, configure it as usual and call `ownership-verifications`. The other tenant's unverified row is inert: it is never routed to, never sends, and cannot be verified without a credential that reaches the number. Neither tenant sees the other's record. Disabling an integration releases its number. Only if a number moved at Meta while the old tenant stays enabled with a stale proof does a platform operator have to clear it (ADR 0024, issue 210 addendum).
- Webhook ingress (#139) resolves a tenant only through `VerifiedPhoneNumberMappings` (the security-definer `dokene.resolve_verified_whatsapp_tenant`), which returns the tenant id of a verified, enabled mapping and nothing else. `OutboundSafetyControl.evaluate` checks the global switch, the tenant switch and then the mapping, in that order; a disabled integration or one without a current proof is `INTEGRATION_DISABLED`.
- Each write appends `INTEGRATION_UPDATED` in the same transaction with the `enabled` flag only, and each real switch flip appends `OUTBOUND_KILL_SWITCH_CHANGED` with the new switch value only. If the audit append fails, the change rolls back.

### Disabling and re enabling outbound sending

Two switches, both off until someone turns them on, both read inside the send claim on every send (spec 0008, ADR 0023 §3.3 and §4.3 steps 3 and 4).

**The global switch** is the deployment property `dokene.messaging.outbound.enabled` (`DOKENE_MESSAGING_OUTBOUND_ENABLED`), default `false`. It is bound once at startup and never re read, so:

1. set the variable (`true` to allow sends, `false` to stop them) and restart the backend;
2. confirm the state in the startup log: exactly one `INFO` line `Outbound messaging global switch enabled=true` (or `=false`) from `WhatsAppOutboundSafetyControl`;
3. with it off, every send in every tenant is refused with `OUTBOUND_DISABLED_GLOBALLY` before the tenant's row is read. No route, tenant setting or request header can change it; a value that is not a boolean literal stops startup with an error that names the property.

**The tenant switch** is the `outbound_enabled` column of the tenant's integration row, flipped by an `INTEGRATION_MANAGE` member:

1. `GET /api/integrations/whatsapp` for the current `ETag`;
2. `PUT /api/integrations/whatsapp/outbound` with `If-Match: "<version>"` and `{ "enabled": false }` (or `true`);
3. confirm with `GET` (`outboundEnabled`) and the `OUTBOUND_KILL_SWITCH_CHANGED` audit row attributed to the member who flipped it.

The gate checks the global switch, then the tenant switch, then the integration (`enabled` with a current ownership proof), and stops at the first that refuses: `OUTBOUND_DISABLED_GLOBALLY`, `OUTBOUND_DISABLED_FOR_TENANT`, `INTEGRATION_DISABLED`. A flip that commits after a claim cannot retract a provider call already in flight (ADR 0028 race semantics); it stops later sends only, and a refused message stays `APPROVED` with no attempt row. If the tenant's row cannot be read when the gate runs (missing row, or the lookup fails), the send is refused with a `500` and nothing is sent (P3-KILL-07). Neither switch affects inbound webhooks: delivery reports for messages already sent are still recorded.

### Secret references and the local secret source (spec 0006, issue 142)

A stored `secretRef` is only a name. `IntegrationSecretResolver` turns it into the credential on the server, inside one scope, and only when a send, a template refresh or a webhook check needs it. Each scope states its purpose: `Tenant(tenantId, WHATSAPP_ACCESS_TOKEN)` for the tenant's own token, `Deployment(WEBHOOK_VERIFY_TOKEN)` or `Deployment(APP_SECRET)` for the deployment's webhook secrets. A tenant scope can only match that tenant's entries, never a deployment entry or another tenant's, whatever key it carries. Every refusal is a `SecretUnavailableException` with a closed category (`MISSING`, `WRONG_PURPOSE`, `SOURCE_DISABLED`), the scope kind and the tenant id, and nothing else: no reference string, no value, no cause. The safety control resolves no secret: the one consumer on the send path is the Graph adapter, which maps every category to `RejectedPermanently(AUTHENTICATION)` on a send (#143); a template refresh and a webhook check fail closed the same way in their own adapters. The log line is `Secret unavailable category=... scope=... tenant=...`, one `WARN` per denial from the local source and a single `WARN` per process from the disabled one.

**Selecting a source.** `DOKENE_INTEGRATION_SECRETS_SOURCE` (property `dokene.integration.secrets.source`) selects exactly one resolver bean. Blank, the default and what the dev stack ships with, selects the disabled resolver: every lookup is `SOURCE_DISABLED`, so nothing sends and no template becomes sendable. `local` selects the local source below. Any other value refuses to start, and the message names the property and the supported values but never the offending value, since a typo there could be a pasted secret. There is no fallback between sources.

**The local source (laptops and staging, never production).** Entries live in configuration kept outside Git, one group per secret:

| Field | Rule |
| --- | --- |
| `scope` | exactly `tenant` or `deployment`, lowercase |
| `tenant-id` | the tenant UUID; required for `tenant`, must be unset (not blank) for `deployment` |
| `ref` | the bare key the tenant stored, or the deployment property names (`^[A-Za-z0-9_-]{1,128}$`) |
| `purpose` | the exact constant: `WHATSAPP_ACCESS_TOKEN` for a tenant entry, `WEBHOOK_VERIFY_TOKEN` or `APP_SECRET` for a deployment entry |
| `value` | the secret, non blank, used exactly as bound |

`(scope, tenant-id, ref)` is unique, so the webhook verify token and the app secret need two distinct deployment references. The same bare key may exist under two tenants, or as a tenant entry and a deployment entry; each owner gets its own value. Entries are validated at startup in index order and field order (`scope`, `tenant-id`, `ref`, `purpose`, `value`), then for duplicates; the first violation stops the application with `dokene.integration.secrets.local.entries[<n>].<field> is invalid` (or `is a duplicate`) and nothing more. A gap in the indices (entries 0 and 2 with no 1) is a binding failure rather than a validation failure; `SecretBindFailureAnalyzer` reports it by property name only, because Spring Boot's own report would print the stranded values. When the source is blank the entries are ignored entirely, but they are still bound, so the contiguous index rule applies even then. At startup the source logs one INFO line with the entry count, so an empty list under `local` is visible.

Two ways to supply them:

1. **Environment variables**: `DOKENE_INTEGRATION_SECRETS_LOCAL_ENTRIES_<n>_SCOPE`, `_TENANT_ID`, `_REF`, `_PURPOSE`, `_VALUE`, with `<n>` contiguous from 0. `.env.example` shows the shape with placeholders. Values are not relaxed by Spring, so `_SCOPE` stays lowercase and `_PURPOSE` is the exact constant name. This route reaches a host-run backend (`set -a; . ./.env; set +a; ./gradlew bootRun`). The Compose backend forwards only `DOKENE_INTEGRATION_SECRETS_SOURCE`, so for a container add the indexed variables in a compose override file, or use the mounted file below with a volume and `SPRING_CONFIG_ADDITIONAL_LOCATION` in that override.
2. **A mounted file**: point the standard `SPRING_CONFIG_ADDITIONAL_LOCATION` at a directory holding an `application.yml` with `dokene.integration.secrets.local.entries` (a Compose or Kubernetes secret mount). This is the sane route for more than a handful of entries.

A value is used exactly as bound. A trailing newline in a mounted file or a quoted space is part of the secret; this is the usual cause of a token that looks right and is refused by the provider.

**Restart and rotation.** The local source is read once at startup and never again, so a changed value needs a restart. To rotate a tenant token in place (same `secretRef`): change the value in the configuration, restart the backend, then `POST /api/integrations/whatsapp/credential-rotations` with `If-Match` so `credential_revision` moves and approvals bound to the old revision fail with `APPROVAL_INVALIDATED`. A production secret store resolves a new value live, so there the restart step disappears and only the rotation call remains.

**What stays in memory.** Spring binds each value as a `String` in the environment and in the bound properties bean for the life of the process. The resolver keeps its own `char[]` copies, hands out a fresh copy per resolve, redacts every `toString`, and actuator stays closed, so neither `/actuator/env` nor `/actuator/configprops` can show them. This is the same exposure the OpenAI API key already has and is accepted for a source that is explicitly not for production.

**The production adapter boundary.** A real deployment gets a new `SecretSourceType` (a cloud secret manager or Vault) and a new resolver class behind the same port and the same entry semantics: owner and purpose carried by the store's metadata or path layout, read live at resolve time. Nothing in the port, the consumers or this section's rules changes for it; it is tracked in the scope's later list.

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

The Meta adapter reports four result classes (ADR 0027 §4):

- `Accepted`: Meta returned a message id. Acceptance is not delivery.
- `RejectedPermanently`: a definitive refusal, normalized to a `FailureCategory`.
- `FailedTransiently`: a definitive refusal that proves nothing was taken, such as throttling, a 4xx "service unavailable" code, an unreachable provider or a failed TLS handshake.
- `OutcomeUnknown`: a timeout after sending, a redirect, any 5xx (whatever Meta code it carries), or an unusable success body.

`OutcomeUnknown` is never retried automatically. Only the integer Meta error code is read; error text, trace ids and bodies are discarded.

The dispatcher maps these results through the message state machine. An exception that escapes the port counts as `OutcomeUnknown`. A transient failure returns the message to `APPROVED`, where only a new operator send, which passes the whole policy again, can try once more, up to the attempt cap. An unknown outcome keeps the message `SENDING` until a human resolves it (T12). A claim whose outcome was never recorded is turned into an unknown outcome by the recovery processor (ADR 0028 §5), never resent.

## Future channels

Potential future channels include:

- email;
- SMS;
- RCS;
- push notifications;
- other business messaging systems.

Adding a channel must not weaken consent, audit, authorization, or idempotency guarantees.
