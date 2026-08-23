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

A durable message state machine should be explicit.

Recommended initial states:

```text
DRAFT
  ↓
PENDING_APPROVAL
  ↓
APPROVED
  ↓
QUEUED
  ↓
SENT
  ↓
DELIVERED
```

with alternate outcomes such as:

```text
REJECTED
CANCELLED
FAILED
```

Transitions should be validated. Do not allow arbitrary status mutation.

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
