# ADR 0024: Phase 3 Messaging Contract Freeze: Executable Fixtures, Approval Fingerprint, Ports and Trusted Contexts

## Status

Accepted. Builds on [ADR 0023](0023-phase-3-outbound-messaging-contracts.md) and does not change its module ownership, state machine, routes, error mapping or permission matrix. It adds one error code (`APPROVAL_INVALIDATED`), one send policy step (5b) and two detail response members (`contactRemoved`, `failureCategory`). Issue: [#132](https://github.com/stevdrey/dokene/issues/132), part of epic [#130](https://github.com/stevdrey/dokene/issues/130).

## Context

ADR 0023 settled the Phase 3 design in prose, and [#133](https://github.com/stevdrey/dokene/issues/133) built the `OutboundMessage` aggregate, the `MessagingProvider` port and the provider-attributed audit path. The epic splits the rest of Phase 3 into two tracks that must build in parallel against fakes:

- the core track: persistence, approval, dispatch and the Meta adapter;
- the collaboration track: secrets, webhook verification, safety policy and UI components.

Parallel work needs four more things to be fixed first.

1. **One executable contract both stacks test against.** Today the frontend mirrors backend types by hand (ADR 0015), and nothing fails when one side drifts.
2. **A rule for what an approval binds.** ADR 0023 §2 makes bodies immutable after submit. It does not say what happens when something the approval relied on changes between approval (T2) and send (T6), for example:
   - the template mapping;
   - the sending number;
   - the recipient's consent evidence.
3. **The remaining ports.** These are template resolution, the integration-side safety control, secret resolution and the verified webhook event. Each needs a stated owner and a fail-closed rule.
4. **The trusted non-member contexts.** These are the contexts in which dispatch, webhooks and a later worker run.

## Decision

### 1. Contract package and versioning

The v1 HTTP contract for `/api/messages/**` has three representations.

- **Java:** `messaging.api.MessagingApiContract`, a set of nested request, response and problem records.
- **TypeScript:** `frontend/src/features/messages/types`, which holds string unions, `as const` value arrays, the code-to-status map and `parseMessagingProblem`.
- **Shared fixtures:** synthetic JSON in `backend/src/test/resources/contracts/messaging/v1/`, covering enums, message details, list pages, event histories, requests and problems.

The fixtures sit in backend test resources so the backend reads them from the classpath. The frontend reads them with Vite `?raw` imports: `server.fs.allow` admits exactly that folder, and the frontend workflow's path filter includes it.

Each consumer has a contract test.

- **Backend, `MessagingContractFixturesTest`:**
  - Every valid example deserializes into its record, using a mapper with `FAIL_ON_UNKNOWN_PROPERTIES`.
  - Re-serializing it gives the same JSON. Responses must emit every member, nulls included; requests may omit optional members.
  - Every `invalid` example is rejected.
  - For each request, the valid examples together name every record component. The round trip ignores omitted optional members, so without this check a new optional member would pass unnoticed.
  - `enums.json` equals the Java enums, and every `MessagingErrorCode` has exactly one problem with its fixed status.
- **Frontend, `contractFixtures.test.ts`:**
  - Every response interface has a `Shape<T>` table. `tsc` fails if the table misses or adds a member.
  - Every fixture must match its table exactly: no extra member, no missing member, known enum values and a valid null.
  - Invalid requests and invalid problems must be refused.

The two tests react to a fixture change as follows.

| Change to a fixture | Rule |
| --- | --- |
| Rename, remove or retype a member, or remove an enum value | Breaking. Add a `v2` directory and amend this ADR; `v1` is never edited in place. |
| Add an optional member or an enum value | Allowed only in the PR that updates the Java records, the TypeScript contract and the fixtures together. The contract owner ([#132](https://github.com/stevdrey/dokene/issues/132)) reviews it, and both workflows must pass. |

Fixture data is synthetic only:

- `7a5c0000-…` UUIDs;
- `+506000000xx` numbers;
- `wamid.SYNTHETIC-…` ids.

The controllers ([#136](https://github.com/stevdrey/dokene/issues/136), [#138](https://github.com/stevdrey/dokene/issues/138)) must use these records and read request bodies at least as strictly as the contract test does: unknown members and unknown enum values are `400 INVALID_INPUT`. Spring's default mapper ignores unknown members, so each controller proves its strictness with its own test.

Clarifications to the ADR 0023 §3.1 representation:

- **`contactRemoved`** appears on detail and list items. It is derived, and true when the customer no longer owns the snapshotted contact (spec 0001, contact lifecycle rule).
- **`failureCategory`** on the detail is the message-level category. It is set by T9, T10 at the attempt limit, T12 and T16, and is `null` otherwise.
- **`attempts[]`** is ordered by attempt number. Its last element is what ADR 0023 §4.4 calls the "last attempt".
- **`createdByMembershipId`** is included, as ADR 0023 already anticipated. Notes, the `sendKey` and the approval fingerprint are never returned.
- **`GET /api/messages/{id}/events`** returns `{ messageId, events[] }`. Events carry no membership id.
- **`GET /api/messages`** returns `{ items[], nextCursor }`, like the follow-up queue.
- **Problem bodies** have `type` fixed to `about:blank` and `title` fixed to the reason phrase of the status. The `status` itself is a function of `code`.

Template and integration endpoints (ADR 0023 §3.2 and §3.3) add their records and fixtures under the same rules, in [#135](https://github.com/stevdrey/dokene/issues/135) and [#134](https://github.com/stevdrey/dokene/issues/134).

### 2. Approval fingerprint

`messaging.domain.ApprovalFingerprint` (scheme `approval/v1`) is the SHA-256 of a canonical encoding. The encoding writes the scheme label, then each field as a 4-byte big-endian length plus its UTF-8 bytes. This is the same encoding `RequestFingerprint` uses, through one shared helper.

The fields, in this order:

| Group | Fields |
| --- | --- |
| Identity | tenant id, message id, customer id, contact id |
| Recipient and content | recipient E.164, channel, semantic action, template intent, locale, body |
| Policy snapshot | `sourcePolicyVersion`, the snapshot the operator submitted against |
| Consent revision | `changedAt` of the contact's WhatsApp consent evidence, truncated to microseconds to match Postgres |
| Template mapping | mapping version, provider template name, language, parameter count, then each parameter name in order |
| Parameter values | the exact ordered values the provider will receive, one per parameter name |
| Sender identity | integration id, `phoneNumberId`, `businessAccountId` |

Deliberately excluded:

- **The message version.** It advances with sends and webhooks.
- **The global and tenant kill switches and the integration enabled flag.** Send policy steps 3 and 4 check them live, and toggling a switch off and on must not void approvals.
- **The current follow-up policy version and evaluation.** Step 7 re-evaluates eligibility live; binding it would void approvals on unrelated cadence edits.

When the fingerprint is computed, stored and checked:

1. **At T2 (approve):** it is computed under the customer lock, from the resolved mapping, the rendered parameter values and the integration's configured sender identity (`OutboundSafetyControl.configuredSender`), and stored on the approval record. Kill-switch state is not a T2 guard: an approval can be given while sending is switched off. If the tenant has no integration configured at all, T2 is refused with `INTEGRATION_DISABLED`, because there is no sender to bind. The column belongs to [#136](https://github.com/stevdrey/dokene/issues/136). It is never returned to the browser, written to audit or logged.
2. **At T6, as new send policy step 5b:** it runs after step 5 (`APPROVED` with an `APPROVE` decision). The service recomputes the fingerprint from current state and compares it with the stored value. On a mismatch the send is refused with `409 APPROVAL_INVALIDATED`. The refusal writes no row and changes no state, like every other policy refusal; the message stays `APPROVED`. On a match, the `OutboundSendCommand` is built from that same binding: its `parameters` are exactly the bound `parameterValues`, never re-rendered after the check.
3. **Recovery:** the operator cancels (T5) and submits again. No new transition is added.

How each kind of change is handled:

| Change | Effect |
| --- | --- |
| Edit the text | Cancel and resubmit (bodies are immutable, ADR 0023 §2). |
| Consent revoked | Step 6 refuses the send with `NO_CONTACT_CONSENT`. |
| Consent revoked and later granted again | Still voids the approval, because `changedAt` moved. |
| Template mapping edited | Voids the approval through the mapping version. |
| A parameter value would render differently (for example, the customer name or product changed) | Voids the approval through the parameter values. |
| Integration moved to another number or account | Voids the approval through the sender identity. |

### 3. Ports

| Port | Owner (package) | Consumer | Fail-closed rule |
| --- | --- | --- | --- |
| `MessagingProvider.send(OutboundSendCommand) -> ProviderSendResult` (existing) | `messaging.application`, implemented in `integration.whatsapp` | dispatch (T7 to T11) | Exceptions never cross; a timeout after the request left is `OutcomeUnknown`, never a retry. |
| `DeliveryStatusSink.apply(DeliveryStatusReport)` (existing) | `messaging.application` | webhook boundary | Applied only under a `ProviderContext`. |
| `OutboundSafetyControl.configuredSender(TenantId) -> Optional<SenderRevision>` | `messaging.application`, implemented by `integration` | T2 fingerprint | Returns the configured sender regardless of kill switches and the enabled flag; empty only when no integration exists, which refuses T2 with `INTEGRATION_DISABLED`. |
| `OutboundSafetyControl.evaluate(TenantId) -> Permitted(SenderRevision) \| Blocked(code)` | `messaging.application`, implemented by `integration` | send policy steps 3 and 4, plus the T6 fingerprint sender identity | Read at send time, never cached. `Blocked` admits only `OUTBOUND_DISABLED_GLOBALLY`, `OUTBOUND_DISABLED_FOR_TENANT` and `INTEGRATION_DISABLED`. Missing configuration throws. |
| `TemplateMappingResolver.resolve(SemanticTemplateIntent) -> Resolved \| NotMapped \| Disabled` | `template.application` | submit, approve, send and dispatch | Only `Resolved` continues; it carries the mapping version for the fingerprint. It supersedes `TemplateMappingGate` and its stub when [#135](https://github.com/stevdrey/dokene/issues/135) lands. |
| `IntegrationSecretResolver.resolve(SecretReference) -> ResolvedSecret` | `integration.application` | the Meta adapter and webhook verifier only | An unresolvable reference throws; there is no default or empty secret. `ResolvedSecret` is `AutoCloseable`: consumers read it only through `withValue(callback)`, which hands out a copy and zero-fills that copy when the callback returns or throws; `close()` wipes the held array. `toString` is redacted, and the secret never leaves `integration`. |
| `VerifiedWebhookEvent(provider, providerEventId, tenantId, DeliveryStatusReport)` | `integration.application` | webhook boundary | Built only after the HMAC check on the raw bytes, schema validation, tenant lookup by the stored `phoneNumberId` and dedup on `(provider, providerEventId)`. |
| `VerifiedWebhookEventHandler.handle(VerifiedWebhookEvent)` | `integration.application` | webhook parsers | The only path from a provider event to `DeliveryStatusSink`. Outside `messaging`, only implementations of this handler may depend on the sink; `ModuleDependencyArchitectureTest` enforces this, with a negative-proof fixture. |

Credentials, secret values and raw provider JSON never enter `messaging` types, HTTP DTOs, audit metadata or logs. `messaging` never depends on `integration`, and `template` depends on neither. Both rules are enforced in `ModuleDependencyArchitectureTest`.

### 4. Trusted contexts, audit and fail-closed behaviour

- **Member commands (T1 to T6, T12)** run under the authenticated member's `TenantContext`. In Phase 3, dispatch (T7 to T11) runs synchronously inside the same request and context (ADR 0023 §3.1). No new context type is introduced for it.
- **Webhooks** run with no member. After verification, the `VerifiedWebhookEventHandler` binds `ProviderContext(tenantId)` with `ProviderContextProvider.callWithContext`, opens a correlation scope and calls `DeliveryStatusSink`. If a member context is present, the call is refused. Provider-attributed audit is signed only for the tenant that the `ProviderContext` names. `TransactionalAuditRecorder.messageDeliveryUpdated` already enforces both rules.
- **The Phase 4 asynchronous worker** is reserved as a third trusted context: a tenant plus a durable claim id, with no membership. Its permission to dispatch comes from the committed `QUEUED` claim and the approval fingerprint, never from a member session. It is specified here and built in Phase 4 together with multi-instance claiming.
- **Missing context:** any path that finds no context, or an ambiguous one, throws. No path falls back to a default tenant.
- **Audit:**
  - Message transitions use the existing `MessageAuditPort` and commit in the transition's transaction; an audit failure rolls back the transition.
  - Template and integration changes (`TEMPLATE_MAPPING_UPDATED`, `INTEGRATION_UPDATED`, `OUTBOUND_KILL_SWITCH_CHANGED`) are emitted through `AuditRecorder` by [#135](https://github.com/stevdrey/dokene/issues/135) and [#134](https://github.com/stevdrey/dokene/issues/134). Their metadata is closed: the target UUID and `enabled`.
  - An `APPROVAL_INVALIDATED` refusal is not audited, consistent with other policy refusals.

### 5. Minimum abuse and frequency checks at dispatch

Phase 3 dispatch enforces, fail closed and in ADR 0023 §4.3 order:

- both kill switches and the integration enabled flag (steps 3 and 4);
- the approval fingerprint (step 5b);
- live consent, do-not-contact and eligibility (steps 6 and 7);
- at most one open message per customer, and no message `SENT`, `DELIVERED` or `READ` to the customer since the current cadence anchor (step 8);
- the attempt limit (step 10);
- `dokene.messaging.send-rate-limit.per-tenant-per-minute`, which returns `429 RATE_LIMITED` with `Retry-After`.

Quiet hours, per-day caps, scheduled sending and provider health throttling are Phase 4.

## Alternatives considered

- **Put fixtures in a neutral top-level `contracts/` directory.** Rejected in favour of backend test resources. The backend owns the server contract and reads the fixtures from the classpath. The frontend precedent of reading a sibling folder through `?raw` already exists (`infra/docker/frontend`).
- **Generate TypeScript from Java, or adopt JSON Schema, ajv or zod.** Deferred. That means a new toolchain and new dependencies for a small closed contract. Shared fixtures catch drift in both directions without them.
- **On fingerprint mismatch, move the message back to `PENDING_APPROVAL`.** Rejected. It adds a system-driven transition and audit type. It also makes a refused send mutate state, unlike every other policy refusal.
- **Bind the current follow-up policy version in the fingerprint.** Rejected. Eligibility is re-evaluated live at T6, and binding the version would void approvals on unrelated cadence edits.
- **Amend ADR 0023 in place.** Rejected by the task owner. The fingerprint, ports and context decisions are new decisions, not corrections.

## Consequences

- Each track can build against frozen records, ports and fixtures. Any incompatible change fails in both workflows, and a fixture-only change runs the frontend workflow too.
- An approval vouches for a specific recipient, content, template revision and sending identity. A change to any of them after approval needs a fresh human approval.
- [#136](https://github.com/stevdrey/dokene/issues/136) adds the fingerprint column to the approval record and the step 5b check, and [#134](https://github.com/stevdrey/dokene/issues/134) provides the sender identity through `OutboundSafetyControl`.
- No endpoint, controller, migration or provider call is enabled by this decision.
