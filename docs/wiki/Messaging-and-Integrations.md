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

## Future channels

Potential future channels include:

- email;
- SMS;
- RCS;
- push notifications;
- other business messaging systems.

Adding a channel must not weaken consent, audit, authorization, or idempotency guarantees.
