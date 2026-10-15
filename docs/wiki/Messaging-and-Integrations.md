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

The template registry ([ADR 0025](../adr/0025-whatsapp-template-registry-and-dispatch-policy.md)) stores one mapping per semantic intent and tenant: provider template name, language, ordered parameter names and an `enabled` flag, behind `GET /api/templates`, `PUT /api/templates/{templateIntent}` and `POST /api/templates/{templateIntent}/approval-refresh`. Approval is provider evidence, never operator input: the refresh asks Meta's Graph API with the tenant's own credential and records the status, check time, business account and mapping revision. A mapping is sendable only when it is enabled, `APPROVED` for its current revision and business account, and the evidence is younger than `dokene.template.approval-max-age` (24 hours by default). Editing a mapping drops the evidence and voids open approvals; a failed refresh drops it too. Phase 3 never sends free-form text: with no trusted customer-service-window evidence it sends only a verified template. The Graph API host is allow-listed (`graph.facebook.com`, loopback test servers, or hosts explicitly listed in `dokene.integration.whatsapp.graph.additional-trusted-hosts`) because tenant credentials are sent to it. Approval evidence does not yet prove the template's placeholders match the stored parameters; that check is a required gate before live dispatch (#138).

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

`tenant_messaging_integrations` holds one `META_WHATSAPP` record per tenant under forced row level security (runtime grants `SELECT, INSERT, UPDATE`). The Meta `phone_number_id` and `business_account_id` are numeric identifiers and are stored apart from the optional human `display_phone_number` (E.164). `(provider, phone_number_id)` is globally unique, because webhooks resolve the tenant from it.

| Route | Permission | Behaviour |
| --- | --- | --- |
| `GET /api/integrations/whatsapp` | `INTEGRATION_READ` (all roles) | Returns `enabled`, `outboundEnabled`, the identifiers, `displayPhoneNumber`, `secretRef`, `webhookVerified` and `version` with a strong `ETag`; `404 NOT_FOUND` when none exists. `outboundEnabled` (tenant kill switch, #144) and `webhookVerified` (#139) are fixed to `false`, fail closed, until those features persist their state. |
| `PUT /api/integrations/whatsapp` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | Exactly one precondition is required, else `400`: `If-None-Match: *` creates the record (`409 STALE_VERSION` if one already exists) and `If-Match: "<version>"` replaces it (stale `409 STALE_VERSION`). |
| `POST /api/integrations/whatsapp/credential-rotations` | `INTEGRATION_MANAGE` (OWNER, ADMIN) | Records that the secret behind the unchanged `secretRef` was replaced in the store. Strong `If-Match`, no body; bumps `credential_revision` and the version and is audited; `404` when no integration exists. |

- Tenant-selection failures (missing, duplicate or malformed `X-Tenant-Id`, no membership) are rejected by `TenantContextRequestFilter` before the controller and keep the framework's empty-body `400`/`403`, as ADR 0023 keeps the 401/403 semantics unchanged. Every failure after the tenant is resolved uses the closed problem+json shape.
- Tenant and actor come from the verified context, never the body. Unknown or wrongly typed members, including any raw token, are rejected with `400 INVALID_INPUT`.
- The body carries a `secretRef`, a bare key (`^[A-Za-z0-9_-]{1,128}$`). Only the reference is stored: it is resolved by `IntegrationSecretResolver` inside the provider adapter at send time (fail closed: unknown, missing or wrong-scope references block the send), never when configuration is written, so the write path needs no resolver. Secret values are never stored, returned, logged or audited, and nothing contacts Meta to validate them.
- A phone number id already mapped by another tenant is refused with the same `400 INVALID_INPUT` as any malformed id, so the API never reads or names another tenant's record. Because the mapping is globally unique, an admin can still learn that a candidate id is unavailable; this is an accepted residual risk tracked in the threat model.
- Rotating a secret in place (same `secretRef`): replace it in the store, then `POST /api/integrations/whatsapp/credential-rotations` with `If-Match` (no body). Changing `secretRef` bumps `credential_revision` too, which is part of the approval sender identity: open approvals then fail the send with `APPROVAL_INVALIDATED`. Enabling or disabling does not void approvals; the enabled flag is read live at send time.
- The `ETag` is the configuration concurrency token (the stored `version`). `outboundEnabled` and `webhookVerified` are live runtime state, so `GET` and `PUT` send `Cache-Control: no-store` and never answer `304`.
- Mapping ownership is **not verified** by this API: every stored mapping is unverified (`webhookVerified=false`) until #139 proves ownership. The webhook must not resolve tenants from unverified mappings and the send path must fail closed on `webhookVerified=false`; a squatted id is released out of band by a platform operator until verification-based reclaim exists (ADR 0024 addendum).
- Each write appends `INTEGRATION_UPDATED` in the same transaction with the `enabled` flag only. If the audit append fails, the change rolls back.
- The `OutboundSafetyControl` implementation that combines this record with the kill switches belongs to the kill switch work (#144).

### Secret references and the local secret source (spec 0006, issue 142)

A stored `secretRef` is only a name. `IntegrationSecretResolver` turns it into the credential on the server, inside one scope, and only when a send, a template refresh or a webhook check needs it. Each scope states its purpose: `Tenant(tenantId, WHATSAPP_ACCESS_TOKEN)` for the tenant's own token, `Deployment(WEBHOOK_VERIFY_TOKEN)` or `Deployment(APP_SECRET)` for the deployment's webhook secrets. A tenant scope can only match that tenant's entries, never a deployment entry or another tenant's, whatever key it carries. Every refusal is a `SecretUnavailableException` with a closed category (`MISSING`, `WRONG_PURPOSE`, `SOURCE_DISABLED`), the scope kind and the tenant id, and nothing else: no reference string, no value, no cause. Consumers treat every category the same way: `INTEGRATION_DISABLED` in the safety control (#144) and `RejectedPermanently(AUTHENTICATION)` on a send (#143). The log line is `Secret unavailable category=... scope=... tenant=...`, one `WARN` per denial from the local source and a single `WARN` per process from the disabled one.

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

## Future channels

Potential future channels include:

- email;
- SMS;
- RCS;
- push notifications;
- other business messaging systems.

Adding a channel must not weaken consent, audit, authorization, or idempotency guarantees.
