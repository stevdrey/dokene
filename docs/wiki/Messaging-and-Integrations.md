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
- the approval fingerprint (`approval/v2`): an approval binds recipient, content, template mapping revision, exact parameter values, sending identity and consent revision; if any of them changes before send, the send is refused with `APPROVAL_INVALIDATED` and the operator cancels and resubmits.

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
- **Responses.** Every command answers with the current detail representation and its `ETag`. Message responses are `Cache-Control: no-store` because `contactRemoved` is derived live. Refusals are closed-code `application/problem+json`; an audit failure is `503` with an empty body and rolls the change back.

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

A disable or revocation that commits after the claim cannot retract a call already in flight; it stops later sends only. Application duplicate suppression is not proof of exactly-once delivery by the provider. Live sending stays off until the kill switch work (#144) and the template schema check (#218) land.

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

The template registry ([ADR 0025](../adr/0025-whatsapp-template-registry-and-dispatch-policy.md)) stores one mapping per semantic intent and tenant: provider template name, language, ordered parameter names and an `enabled` flag, behind `GET /api/templates`, `PUT /api/templates/{templateIntent}` and `POST /api/templates/{templateIntent}/approval-refresh`. Approval is provider evidence, never operator input: the refresh asks Meta's Graph API with the tenant's own credential and records the status, check time, business account and mapping revision. A mapping is sendable only when it is enabled, `APPROVED` for its current revision and business account, and the evidence is younger than `dokene.template.approval-max-age` (24 hours by default). Editing a mapping drops the evidence and voids open approvals; a failed refresh drops it too. Phase 3 never sends free-form text: with no trusted customer-service-window evidence it sends only a verified template. The Graph API host is allow-listed (`graph.facebook.com`, loopback test servers, or hosts explicitly listed in `dokene.integration.whatsapp.graph.additional-trusted-hosts`) because tenant credentials are sent to it. Meta's `APPROVED` is recorded only when the template's component schema matches the mapping (#218): text parameters in the body only, positional `{{1}}..{{n}}` matching the number of parameter names or named placeholders matching the stored names. Otherwise the refresh stores `SCHEMA_MISMATCH`, `SCHEMA_UNSUPPORTED` or `SCHEMA_UNREADABLE`, which are never sendable and expose no provider text. Approvals recorded before this check were reset to `UNVERIFIED` and must be refreshed; the migration is expand-only, and an approval rewritten by an instance of the previous release during a rolling deploy reads as unchecked and stays not sendable until refreshed again. Until the named send path lands, a template verified with named placeholders stays not sendable.

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

### WhatsApp integration API (V20, issue 134)

`tenant_messaging_integrations` holds one `META_WHATSAPP` record per tenant under forced row level security (runtime grants `SELECT, INSERT, UPDATE`). The Meta `phone_number_id` and `business_account_id` are numeric identifiers and are stored apart from the optional human `display_phone_number` (E.164). `(provider, phone_number_id)` is unique among **verified** mappings only (V24, issue 210): several tenants may hold an unverified claim of one number, but only the tenant that proves ownership is ever routed to or sends from it.

| Route | Permission | Behaviour |
| --- | --- | --- |
| `GET /api/integrations/whatsapp` | `INTEGRATION_READ` (all roles) | Returns `enabled`, `outboundEnabled`, the identifiers, `displayPhoneNumber`, `secretRef`, `webhookVerified` and `version` with a strong `ETag`; `404 NOT_FOUND` when none exists. `outboundEnabled` (tenant kill switch, #144) is fixed to `false`, fail closed, until that feature persists its state. `webhookVerified` is the stored ownership proof for the current number, business account and credential revision. |
| `PUT /api/integrations/whatsapp` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | Exactly one precondition is required, else `400`: `If-None-Match: *` creates the record (`409 STALE_VERSION` if one already exists) and `If-Match: "<version>"` replaces it (stale `409 STALE_VERSION`). Any well-formed `phoneNumberId` is accepted and stored unverified; a `PUT` is never throttled. Changing `phoneNumberId`, `businessAccountId` or `secretRef`, or disabling the integration, clears the ownership proof; re-enabling does not restore it. |
| `POST /api/integrations/whatsapp/credential-rotations` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | Records that the secret behind the unchanged `secretRef` was replaced in the store. Strong `If-Match`, no body; bumps `credential_revision` and the version, clears the ownership proof and is audited; `404` when no integration exists. |
| `POST /api/integrations/whatsapp/ownership-verifications` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | Proves at Meta, with the tenant's own credential, that the stored business account lists the stored phone number, and records the proof (`webhookVerified=true`). Strong `If-Match`, no body; bumps the version and is audited. `400 INVALID_INPUT` when not proven or when another tenant already verified the number (indistinguishable), `400 INVALID_INPUT` for a disabled integration (no Meta call, no budget), `503 PROVIDER_UNAVAILABLE` when Meta gives no conclusion or the tenant's token is invalid or expired (`401`), `409 STALE_VERSION`, `404`, and `429 RATE_LIMITED` while the rejected-proof budget is spent. |

- Tenant-selection failures are rejected by `TenantContextRequestFilter` before the controller, with unchanged statuses and audit, and use the closed problem+json shape on every Phase 3 route (`/api/messages/**`, `/api/customers/{customerId}/messages`, `/api/integrations/**`, `/api/templates/**`): a missing `X-Tenant-Id` or no membership is `403 FORBIDDEN`, a duplicated or malformed one is `400 INVALID_INPUT`. Each module claims its own routes through a `TenantSelectionRejectionResponder` (ADR 0024, issue 207 addendum); other endpoints keep the empty-body `400`/`403`.
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
- The `ETag` is the configuration concurrency token (the stored `version`); recording or clearing a proof bumps it. `outboundEnabled` is live runtime state, so `GET` and `PUT` send `Cache-Control: no-store` and never answer `304`.
- **Reclaiming a pre-registered number.** If another tenant mapped your number first, configure it as usual and call `ownership-verifications`. The other tenant's unverified row is inert: it is never routed to, never sends, and cannot be verified without a credential that reaches the number. Neither tenant sees the other's record. Disabling an integration releases its number. Only if a number moved at Meta while the old tenant stays enabled with a stale proof does a platform operator have to clear it (ADR 0024, issue 210 addendum).
- Webhook ingress (#139) resolves a tenant only through `VerifiedPhoneNumberMappings` (the security-definer `dokene.resolve_verified_whatsapp_tenant`), which returns the tenant id of a verified, enabled mapping and nothing else. `OutboundSafetyControl.evaluate` checks the global switch, the tenant switch and then the mapping, in that order; a disabled integration or one without a current proof is `INTEGRATION_DISABLED`.
- Each write appends `INTEGRATION_UPDATED` in the same transaction with the `enabled` flag only. If the audit append fails, the change rolls back.
- The state behind the global and tenant switches in `OutboundSafetyControl.evaluate` belongs to the kill switch work (#144); until then the global switch blocks every send.

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
