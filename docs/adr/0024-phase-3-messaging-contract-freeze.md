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
  - Every `invalid` example is rejected for the rule it was written to prove: the test pins the expected innermost cause per example (unknown member, unknown enum value, missing required value or record invariant).
  - The Java type of every component of every contract record is pinned against an explicit schema, because a retype with the same JSON form (`UUID` or `Instant` to `String`) would pass a round trip.
  - For each request, the valid examples together name every record component. The round trip ignores omitted optional members, so without this check a new optional member would pass unnoticed.
  - `enums.json` equals the Java enums, and every `MessagingErrorCode` has exactly one problem with its fixed status.
- **Frontend, `contractFixtures.test.ts`:**
  - Every response and request interface has a `Shape<T>` table whose rule types are derived from the interface's member types (kind, enum values, nullability and optionality). `tsc` fails if a member is added, removed, retyped or changes nullability or optionality without its rule. The members `parseMessagingProblem` accepts are derived from `MessagingProblem`, and the problem fixtures are also checked against `Shape<MessagingProblem>`.
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

`messaging.domain.ApprovalFingerprint` (scheme `approval/v1`; `approval/v2` and then `approval/v3` since the addenda below) is the SHA-256 of a canonical encoding. The encoding writes the scheme label, then each field as a 4-byte big-endian length plus its UTF-8 bytes. This is the same encoding `RequestFingerprint` uses, through one shared helper.

The fields, in this order:

| Group | Fields |
| --- | --- |
| Identity | tenant id, message id, customer id, contact id |
| Recipient and content | recipient E.164, channel, semantic action, template intent, locale, body |
| Policy snapshot | `sourcePolicyVersion`, the snapshot the operator submitted against |
| Consent revision | the contact policy version (`customer_consent_history.policy_version`) of the history row that started the contact's current WhatsApp `GRANTED` run: the first `GRANTED` row after the latest non-`GRANTED` row, or the first row when consent was never withdrawn. Every consent change advances the version by compare-and-set, so a revocation followed by a re-grant always yields a new value, and re-saving `GRANTED` over `GRANTED` does not void approvals. A timestamp is not used because two changes can share one at database precision |
| Template mapping | mapping version, provider template name, language, verified parameter format (since `approval/v3`), parameter count, then each parameter name in order |
| Parameter values | the exact ordered values the provider will receive, one per parameter name |
| Sender identity | integration id, `phoneNumberId`, `businessAccountId` |

Deliberately excluded:

- **The message version.** It advances with sends and webhooks.
- **The global and tenant kill switches and the integration enabled flag.** Send policy steps 3 and 4 check them live, and toggling a switch off and on must not void approvals.
- **The current follow-up policy version and evaluation.** Step 7 re-evaluates eligibility live; binding it would void approvals on unrelated cadence edits.

When the fingerprint is computed, stored and checked:

1. **At T2 (approve):** it is computed under the customer lock, from the resolved mapping, the rendered parameter values and the integration's configured sender identity (`OutboundSafetyControl.configuredSender`), and stored on the approval record. Kill-switch state is not a T2 guard: an approval can be given while sending is switched off. If the tenant has no integration configured at all, T2 is refused with `INTEGRATION_DISABLED`, because there is no sender to bind. The column belongs to [#136](https://github.com/stevdrey/dokene/issues/136). It is never returned to the browser, written to audit or logged.
2. **At T6, as new send policy step 5b:** it runs after step 5 (`APPROVED` with an `APPROVE` decision). The service recomputes the fingerprint from current state and compares it with the stored value. On a mismatch the send is refused with `409 APPROVAL_INVALIDATED`. The refusal writes no row and changes no state, like every other policy refusal; the message stays `APPROVED`. On a match, the `OutboundSendCommand` is built from that same binding: its `parameters` are exactly the bound `parameterValues`, and its parameter format and names are those of the bound template revision (issue 218 addendum), never re-rendered or re-resolved after the check.
3. **Recovery:** the operator cancels (T5) and submits again. No new transition is added.

How each kind of change is handled:

| Change | Effect |
| --- | --- |
| Edit the text | Cancel and resubmit (bodies are immutable, ADR 0023 §2). |
| Consent revoked | Step 6 refuses the send with `NO_CONTACT_CONSENT`. |
| Consent revoked and later granted again | Still voids the approval, because the re-grant starts a new `GRANTED` run with a higher revision. |
| Consent re-saved as `GRANTED` while already `GRANTED` | Does not void the approval. |
| Template mapping edited | Voids the approval through the mapping version. |
| A parameter value would render differently (for example, the customer name or product changed) | Voids the approval through the parameter values. |
| Integration moved to another number or account | Voids the approval through the sender identity. |

### 3. Ports

| Port | Owner (package) | Consumer | Fail-closed rule |
| --- | --- | --- | --- |
| `MessagingProvider.send(OutboundSendCommand) -> ProviderSendResult` (existing) | `messaging.application`, implemented in `integration.whatsapp` by `GraphApiMessagingProvider` ([ADR 0027](0027-meta-whatsapp-send-adapter.md)) | dispatch (T7 to T11) | Exceptions never cross; a timeout after the request left is `OutcomeUnknown`, never a retry. |
| `DeliveryStatusSink.apply(DeliveryStatusReport)` (existing) | `messaging.application` | webhook boundary | Applied only under a `ProviderContext`. |
| `OutboundSafetyControl.configuredSender(TenantId) -> Optional<SenderRevision>` | `messaging.application`, implemented by `integration` | T2 fingerprint | Returns the configured sender regardless of kill switches and the enabled flag; empty only when no integration exists, which refuses T2 with `INTEGRATION_DISABLED`. |
| `OutboundSafetyControl.evaluate(TenantId) -> Permitted(SenderRevision) \| Blocked(code)` | `messaging.application`, implemented by `integration` | send policy steps 3 and 4, plus the T6 fingerprint sender identity | Read at send time, never cached. `Blocked` admits only `OUTBOUND_DISABLED_GLOBALLY`, `OUTBOUND_DISABLED_FOR_TENANT` and `INTEGRATION_DISABLED`. Missing configuration throws. |
| `TemplateMappingResolver.resolve(SemanticTemplateIntent) -> Resolved \| NotMapped \| Disabled` | `template.application` | submit, approve, send and dispatch | Only `Resolved` continues; it carries the mapping version for the fingerprint. It supersedes `TemplateMappingGate` and its stub; [ADR 0025](0025-whatsapp-template-registry-and-dispatch-policy.md) (#135) implements it. |
| `IntegrationSecretResolver.resolve(SecretScope, SecretReference) -> ResolvedSecret` | `integration.application` | the Meta adapter and webhook verifier only | A reference is a bare key (`^[A-Za-z0-9_-]{1,128}$`: no scheme, path or separator) resolved only inside its scope. A reference stored in tenant integration configuration is resolved with `Tenant(tenantId)` and is confined to that tenant's namespace, so a tenant can never name a deployment secret or another tenant's secret. `Deployment` is used only for references read from configuration properties (verify token, app secret). An unresolvable reference throws; there is no default or empty secret. `ResolvedSecret` takes ownership of the caller's buffer (wiping it), is `AutoCloseable` and thread-safe, and is read only through `withValue(callback)`, which zero-fills the handed-out copy. The callback passes the characters straight to the consuming API and builds a `String` only when that API accepts nothing else, keeping it local to the call. `toString` is redacted, and the secret never leaves `integration`. |
| `VerifiedWebhookEvent(provider, providerEventId, tenantId, DeliveryStatusReport)` | `integration.application` | webhook boundary | Built only after the HMAC check on the raw bytes, schema validation, tenant lookup by the stored `phoneNumberId` and dedup on `(provider, providerEventId)`. Only classes in `integration.whatsapp.webhook` (any `..integration..webhook..` package) may call its constructor; `ModuleDependencyArchitectureTest` enforces this with a negative-proof fixture. |
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

## Addendum (issue 134): credential revision in the sender identity

`SenderRevision` gains `credentialRevision`, the count of secret reference changes for the integration (starts at 1; the reference itself is never hashed). Because the canonical encoding changes, the scheme label moves from `approval/v1` to `approval/v2`, as the label rule requires. Nothing had persisted a v1 fingerprint yet. Rotating the credential therefore voids open approvals, while toggling `enabled` still does not.

## Addendum (issue 218): parameter format in the template revision

A provider template declares its body placeholders either positionally (`{{1}}`) or by name (`{{customer_name}}`), and Meta requires a different request for each: a named placeholder is filled only when the parameter carries its `parameter_name`. The verified format (ADR 0025 §2a) therefore decides what the provider receives, so the approval binds it.

- `TemplateRevision` gains `parameterFormat`, a closed `ApprovalFingerprint.ParameterFormat` (`POSITIONAL`, `NAMED`). The messaging domain cannot depend on the template module, so it mirrors `TemplateParameterFormat`. `ApprovalBindingAssembler` maps one to the other with an exhaustive switch, so a new format cannot reach a fingerprint unmapped.
- The format is encoded right after the language. Because the canonical encoding changes, the scheme label moves from `approval/v2` to `approval/v3`, as the label rule requires.
- A template re-verified under another format after approval fails step 5b with `APPROVAL_INVALIDATED`, and the operator cancels and resubmits.
- `OutboundSendCommand` carries the format and the parameter names of the binding that matched, aligned one to one with the values, so the adapter sends exactly the approved shape.

Every stored `approval/v2` fingerprint stops covering its binding, so open approvals taken before this change are invalidated at send time. This is accepted: live sending is off (the global kill switch is hardcoded until [#144](https://github.com/stevdrey/dokene/issues/144)), so no approval that a customer could be waiting on is lost. No migration rewrites them, because a fingerprint cannot be recomputed without the approver.

## Addendum (issue 134): integration endpoint preconditions and filter-level rejections

- `PUT /api/integrations/whatsapp` states exactly one precondition (ADR 0023 §4.1): `If-None-Match: *` to create, which fails with `409 STALE_VERSION` if the tenant already has an integration, or `If-Match: "<version>"` to replace. A missing, doubled or malformed precondition is `400 INVALID_INPUT`, so nothing is created or replaced unconditionally.
- `outboundEnabled` and `webhookVerified` are part of the response from the start. *`webhookVerified` is superseded by the issue 210 addendum below.* They are fixed to `false` until the tenant kill switch (#144) and webhook verification (#139) own and persist that state.
- *Superseded by the issue 207 addendum below.* Tenant-selection failures raised by `TenantContextRequestFilter` (missing, duplicate or malformed `X-Tenant-Id`, no membership) kept the framework's empty-body `400`/`403` before any controller advice runs, because ADR 0023 keeps those semantics unchanged and the filter is shared by every tenant-scoped endpoint.

## Addendum (issue 134 follow-up review): mapping ownership, credential rotation and ETag scope

- **Mapping ownership gate.** *Superseded by the issue 210 addendum below, which implements the proof and the reclaim path.* Every `(provider, phone_number_id)` mapping stored by `PUT /api/integrations/whatsapp` is **unverified**: an `INTEGRATION_MANAGE` user can name any well-formed id and the API never proves they own it at Meta. `webhookVerified` is therefore `false` for every mapping until persisted verification exists. Acceptance gates: the webhook work (#139) must resolve a tenant only from a verified mapping, must add the proof of ownership (done by the adapter, for example a Graph API read of the number with the tenant's credential, never by this API) and must not route events from `tenant_messaging_integrations` before that; the send path (#142, #144) must treat `webhookVerified=false` as `INTEGRATION_DISABLED` (fail closed). Until verification exists, a squatted id is released out of band by a platform operator (migration role) without showing the legitimate tenant any other tenant's metadata; verification-based reclaim replaces that process in #139.
- **Credential rotation.** `credential_revision` is part of the approval sender identity. It increments when `secretRef` changes and through `POST /api/integrations/whatsapp/credential-rotations` (`INTEGRATION_MANAGE`, strong `If-Match`, no body). To rotate a secret in place: replace it in the secret store, then call that operation; the adapter resolves the new value at send time and approvals bound to the previous revision fail with `APPROVAL_INVALIDATED`. Toggling `enabled` is not a rotation and does not void approvals.
- **ETag scope.** The strong ETag of `GET`/`PUT` is the configuration concurrency token: the stored row's `version`. `outboundEnabled` (and, before issue 210, `webhookVerified`) is live runtime state that can change without it, so these responses carry `Cache-Control: no-store` and are never answered with `304`.

## Addendum (issue 208): throttling rejected phone-number-id mappings

*The gate, what counts and the transaction boundary are superseded by the issue 210 addendum below: the budget now guards ownership proofs, not `PUT`. The budget, its configuration and the audit shape are unchanged.*

[#208](https://github.com/stevdrey/dokene/issues/208) mitigates the availability probe left open by the issue 134 follow-up review. ADR 0023 §4.4 defined `429 RATE_LIMITED` with `Retry-After` for the send rate limit only; `PUT /api/integrations/whatsapp` now uses the same closed code, status, body and header. No record, fixture or enum changes.

- **What counts.** Only a phone number id already mapped by another tenant (`uq_tenant_messaging_integrations_phone_number`) is a rejected mapping. It is recognised from the duplicate-key SQLState, not from message text; any duplicate key other than the tenant/provider key counts (fail closed). It is still answered as `400 INVALID_INPUT`, identical to any invalid mapping. Malformed ids are deterministic and leak nothing, so they do not count, and neither do successful writes, stale preconditions or credential rotations.
- **Budget.** An in-memory sliding window per tenant and per actor within the tenant, configured by `dokene.integration.whatsapp.mapping-rejection-limit.tenant-rejections` (10), `actor-rejections` (5) and `window` (15m). State is per instance and is lost on restart. The window arithmetic and the `Retry-After` rounding live in the dependency-free `ratelimit` package, shared with the AI recommendation limiter. Each mapping change reserves one unit of both budgets before it writes and keeps it until the outcome is known, so concurrent attempts can never reveal more conflicts than the budget allows; a success releases its unit.
- **Gate.** While either budget is spent, a `PUT` that creates the integration or changes `phoneNumberId` is refused with `429 RATE_LIMITED` before any write, whatever the candidate. `Retry-After` is the whole seconds until enough rejections leave the window, or one second while the budget is held only by attempts still in flight. A `PUT` that keeps the stored id cannot probe anything, so it is never throttled; disabling the integration always works.
- **Transaction boundary.** `upsert` is not one transaction: a short read decides whether the mapping changes, the budget is reserved and any 429 is audited with no business transaction open, then the write runs in its own transaction and a conflict is counted after it rolled back. A throttled or rejected attempt therefore never holds a connection while its denial is audited. If the stored number changed after the read, the write is refused with `409 STALE_VERSION`.
- **Audit.** Every refused attempt appends exactly one `AUTHORIZATION_DENIED` with permission `INTEGRATION_MANAGE`, as security invariant 12 requires for denied actions. V23 adds two denial reasons to `ck_audit_shape`, and the reason matches the response:
  - `MAPPING_REJECTED` for each conflict, answered `400`, including the one that spends the budget;
  - `RATE_LIMITED` for each throttled attempt, answered `429`.

  Malformed ids and successful writes append no denial. The rows are written in their own transaction (`REQUIRES_NEW`, ADR 0006) after the write transaction rolled back, so they survive it, and they carry no target and no candidate id. A failed audit write answers the fail-closed empty `503`; a conflict whose denial could not be written still counts against the budget.

## Addendum (issue 136): review and approval routes, parameter vocabulary and cancellation permission

[#136](https://github.com/stevdrey/dokene/issues/136) adds the submit, read, approval and cancellation routes of ADR 0023 §3.1 and the T2 half of §2. It does not change the v1 records, fixtures, state machine or role map.

- **No edit route.** The issue asked for draft editing. ADR 0023 §2 keeps bodies immutable, so an edit is a cancellation (T4 or T5) followed by a new submit, and the new message needs its own approval. No `DRAFT` state or edit endpoint is added.
- **Parameter vocabulary.** The fingerprint binds "the exact ordered values the provider will receive", and no module rendered them yet. A template parameter name is now rendered from a closed vocabulary: `customer_name` is the customer's current display name and `message_body` is the stored body. Any other name cannot be rendered deterministically, so the mapping is refused with `TEMPLATE_NOT_MAPPED`. A rendered value that fails `TemplateParameterValues` is refused with `BODY_REJECTED`. Renaming the customer therefore voids open approvals, like any other parameter change. Adding a name to the vocabulary is a code change, reviewed like this one.
- **Language.** The mapping's language (Meta code) must have the same primary subtag as the message locale (BCP 47), for example `es` or `es_MX` for `es-419`. Otherwise `TEMPLATE_NOT_MAPPED`. This is the locale mapping ADR 0025 left to this issue.
- **Binding at T2, same checks at T1.** `ApprovalBindingAssembler` builds the binding under the customer lock from the resolved mapping (`TemplateMappingResolver`), the rendered values, `ContactPolicyRepository.whatsAppConsentRevision` and `OutboundSafetyControl.configuredSender`. Submit (T1) runs the same resolve, language and render checks (`requireRenderable`), so a message approval could never bind (for example a body with a line break) is refused at submit instead of holding the customer's open-message slot. Dispatch (#138) reuses the binding for step 5b with the sender from `evaluate`. The resolver is the only template check: `TemplateMappingGate`, `TemplateMappingStatus` and `ResolverBackedTemplateMappingGate` are removed, as §3 anticipated, which supersedes the ADR 0025 note that submit calls the gate. The fingerprint is stored in `outbound_message_approvals.approval_fingerprint` (V22). A check constraint requires it exactly for `APPROVE`; it is added `NOT VALID`, so an approve row written before V22 stays readable and can never pass step 5b.
- **Sender port.** `integration.application.WhatsAppOutboundSafetyControl` implements `OutboundSafetyControl`. `configuredSender` reads the stored integration on every call. `evaluate` returns `Blocked(OUTBOUND_DISABLED_GLOBALLY)` until the kill-switch policy ([#144](https://github.com/stevdrey/dokene/issues/144)) owns it, so nothing can send through this slice.
- **Cancellation permission.** `@RequiredPermission` gains `anyOf`. The cancellation route declares `{MESSAGE_DRAFT, MESSAGE_APPROVE}` with `anyOf = true`, so a caller holding neither is denied before binding, with one audited denial per listed permission (the caller lacked both). The service still requires the state-specific permission under the message lock (ADR 0023 §5).
- **Caching.** Message responses carry `Cache-Control: no-store` and are never answered with `304`, because `contactRemoved` is derived from live contact rows without a version change. `no-store` alone does not prevent Spring MVC from answering a matching `If-None-Match` with `304` for a `GET` returning an ETag-bearing `ResponseEntity`, so the detail route sets `ETag` and `Cache-Control` on the servlet response, as the integration endpoint does. The `ETag` stays the message version, the strong `If-Match` token for commands.
- **Command responses.** A command's response detail is read inside the command's own transaction while it holds the message row lock (a replay takes that lock too), so the body and `ETag` are one consistent version and a committed command is never reported as failed by a second read or authorization. Standalone detail reads run under one `REPEATABLE READ` snapshot.
- **Shared request strictness.** *Moved by the issue 209 addendum below.* `tenant.api.StrictRequests` holds the one strict JSON request reader and the strong numeric ETag grammar used by the message, template and integration controllers.

## Addendum (issue 207): tenant-selection rejections on Phase 3 routes

[#207](https://github.com/stevdrey/dokene/issues/207) closes the exception recorded in the issue 134 addendum. `TenantContextRequestFilter` still rejects tenant selection before any controller advice runs, with the same statuses and the same audited denial (`TENANT_READ`, `NO_TENANT_CONTEXT`). On Phase 3 routes the rejection now carries the closed problem+json body of ADR 0023 §4.4.

- **Statuses are unchanged** (ADR 0008, ADR 0023 §6): a missing `X-Tenant-Id` or a tenant without an active membership is `403 FORBIDDEN`; a duplicated or malformed selector is `400 INVALID_INPUT`. A missing selector is a tenant failure, not a missing request header, so it stays `403`.
- **SPI in `tenant.security`.** `TenantSelectionRejection` (`INVALID_SELECTOR` 400, `ACCESS_DENIED` 403) owns the status. A module that owns routes registers a `TenantSelectionRejectionResponder` bean, usually by extending `ProblemJsonTenantSelectionResponder`, which writes the module's closed problem record with the rejection's status as `application/problem+json`. That base class serializes each body once, when the bean is created, and refuses to start if a body's own `status` differs from the rejection's, so a response can never carry contradictory statuses. The tenant module never depends on `messaging`, `integration` or `template`.
- **Route ownership.** `messaging.api` claims `/api/messages/**` and `/api/customers/*/messages`, and `integration.api` claims `/api/integrations/**`; both extend `MessagingProblemTenantSelectionResponder`, the single rejection-to-`MessagingErrorCode` mapping. `template.api` claims `/api/templates/**` with its own mapping to `TemplateProblem` (same JSON for the shared codes), because the template module may not depend on messaging. Matching is by path pattern, so a lookalike path outside those trees is not claimed. `TenantSelectionProblemIntegrationTest` checks every registered `/api` handler: each handler of a Phase 3 module must be claimed and no other may be, so a new Phase 3 route without a responder fails the build.
- **Existing endpoints** are handled by no responder and keep the framework's empty-body `400`/`403`.

## Addendum (issue 209): one strong-ETag grammar for every `If-Match`

[#209](https://github.com/stevdrey/dokene/issues/209) extends the shared request strictness to the endpoints that predate Phase 3.

- **Location.** `StrictRequests` moves from `tenant.api` to the module-neutral `http` package. It depends on no Dokene module, and `ModuleDependencyArchitectureTest` enforces this, as it does for `ratelimit`.
- **Every `If-Match`.** The customer, contact-policy, purchase and follow-up controllers parse `If-Match` with `StrictRequests.strongNumericVersion`, as the message, template and integration controllers already did. Unquoted, zero-padded, signed, weak, list and `*` values are `400` everywhere (ADR 0023 §4.1). A present but empty or blank header is malformed, not absent: only an absent optional header keeps the optional precondition (follow-up recommendation and draft) or the body `version` fallback (customer update). Each module keeps its own error body. The SPA always sends the quoted form.
- **Range.** The grammar accepts every non-negative `long` (up to 19 digits) and refuses a value above `Long.MAX_VALUE`. Every ETag is formatted with `StrictRequests.strongEtag`, which refuses a negative version, so every emitted ETag parses back.

## Addendum (issue 137): the send command binds the approved sender

`OutboundSendCommand` gains `expectedSender`, the `ApprovalFingerprint.SenderRevision` recorded at approval. The dispatcher (#142) builds it from the same approval binding as the parameters (§2 step 5b). The Meta adapter ([ADR 0027](0027-meta-whatsapp-send-adapter.md)) re-reads the tenant's integration at send time and refuses with `RejectedPermanently(POLICY_VIOLATION)`, without calling Meta, unless `WhatsAppOutboundSafetyControl.senderRevision(integration)` equals it. The check closes the window between the dispatcher's sender check and the provider call, in which a reconfigured and re-proven integration could otherwise send under an identity the operator never approved. The field is non-secret, the record is internal to the backend (no HTTP fixture changes), and no caller existed before this change.

## Addendum (issue 210): proof of phone number ownership and reclaim

[#210](https://github.com/stevdrey/dokene/issues/210) implements the ownership gate the issue 134 follow-up review left open. It replaces the out-of-band operator release.

- **Uniqueness binds verified mappings only.** V24 drops `uq_tenant_messaging_integrations_phone_number` and adds the partial unique index `uq_tenant_messaging_integrations_verified_phone_number` on `(provider, phone_number_id) WHERE ownership_verified_at IS NOT NULL`. Any number of tenants may hold an unverified claim of one number; at most one may hold the verified mapping. `PUT` therefore accepts any well-formed id, never conflicts on the number and is never throttled, so naming another tenant's number neither blocks the real owner nor reveals that the number is in use.
- **Persisted state.** V24 adds `ownership_verified_at`. The `BEFORE INSERT OR UPDATE` trigger `tg_tenant_messaging_integrations_ownership_proof` rejects, with `check_violation`, a new row that claims a proof and any update that keeps a proof while changing `phone_number_id`, `business_account_id` or `credential_revision`, or while disabling the integration. It only rejects and never rewrites a row, so a proof can never outlive the configuration it was made for, even through a write that bypasses the domain. `webhookVerified` in the API is this persisted proof (`ownership_verified_at IS NOT NULL`), which implies the integration is enabled and routable. It is no longer supplied by `IntegrationReadiness`, which now reports `outboundEnabled` only.
- **Proof.** `POST /api/integrations/whatsapp/ownership-verifications` (`INTEGRATION_MANAGE`, strong `If-Match`, no body) asks the `PhoneNumberOwnershipVerifier` port to prove the stored mapping. The Graph adapter resolves the tenant's own credential (`Tenant` scope, current tenant only) and lists `GET /{businessAccountId}/phone_numbers?fields=id`. The answer is `Proven` only when that list contains the stored `phoneNumberId`. A list that really ends without it, or a `400`/`403`/`404` for the business account, is `NotProven`. Anything else is `Unavailable`: no resolver, a `401` (the tenant's own token is invalid or expired, which is a credential problem and not evidence about ownership), a timeout, a `5xx`, a redirect, a malformed or oversized body, or running out of pages or cursors. No provider text leaves the adapter. The shared bounded HTTP code (one deadline, streamed body cap, no redirects) lives in `GraphApiHttp` and is also used by the template gateway (ADR 0025). Writing configuration still makes no network call.
- **Outcomes.**
  - `Proven`: the proof is recorded against exactly the version that was proven, in a short compare-and-set transaction, and appends `INTEGRATION_UPDATED`. It bumps `version`, so the `ETag` changes.
  - `NotProven`, or `Proven` when another tenant already holds the verified mapping (the partial index rejects the update): `400 INVALID_INPUT`, identical in both cases, so the answer is no oracle for other tenants' mappings.
  - `Unavailable`: `503 PROVIDER_UNAVAILABLE`, and the stored state is unchanged.
  - A disabled integration: `400 INVALID_INPUT` without a provider call and without consuming budget, because the refusal is deterministic and reveals nothing.
  - A configuration change between the read and the write: `409 STALE_VERSION`.
  - Until a production `IntegrationSecretResolver` exists (#142), every proof is `Unavailable`.
- **Transaction boundary.** The provider call runs between a short read and the short write, so no connection is held across the network.
- **Budget.** The issue 208 budget now guards proofs. Each proof reserves a unit before calling the provider. A `400` refusal counts and appends `AUTHORIZATION_DENIED` with `MAPPING_REJECTED` after any write rolled back. While a budget is spent, a proof is refused with `429 RATE_LIMITED` (audited `RATE_LIMITED`) without calling the provider. `Unavailable`, stale preconditions and successes consume nothing. No audit shape change was needed.
- **Reset.** The proof binds the enabled phone number id, the business account and the credential revision. Changing any of them through `PUT`, changing `secretRef`, rotating the credential in place or disabling the integration clears it, and the tenant must verify again (re-enabling does not restore it). Changing the display number keeps it. Disabling therefore releases the number: a tenant that stops using it never keeps it locked.
- **Reclaim.** If another tenant pre-registered the number, the real owner configures it as usual and verifies it with their own credential. The squatter's unverified row stays inert: it is never routed and never sends, and the squatter cannot verify a number their credential does not reach. Neither tenant sees the other's record, identifiers or credential reference. If a number moved at Meta to another business account, the old tenant releases it by disabling or changing its integration. Only an old tenant that stays enabled and inactive still blocks the new owner's proof, and then a platform operator (migration role) clears the stale proof.
- **Routing gate for the webhook (#139).** `VerifiedPhoneNumberMappings.tenantFor(phoneNumberId)` is the only way to map a number to a tenant without a tenant context. It calls the security-definer function `dokene.resolve_verified_whatsapp_tenant(TEXT)`, which is executable by `dokene_runtime` only and returns just the tenant id of a verified, enabled mapping. The "tenant lookup by the stored `phoneNumberId`" that precedes building a `VerifiedWebhookEvent` (§3) must use it and must never read `tenant_messaging_integrations` directly.
- **Send gate (#144).** `WhatsAppOutboundSafetyControl.evaluate` composes send policy steps 3 and 4 (ADR 0023 §4.3) in order: `globalGate` (always `OUTBOUND_DISABLED_GLOBALLY` until #144), `tenantGate` (`OUTBOUND_DISABLED_FOR_TENANT` unless `IntegrationReadiness.outboundEnabled`), then `integrationGate`, where a disabled integration or one without a current proof is `INTEGRATION_DISABLED`. Only then is it `Permitted`. #144 replaces the switch state behind the first two gates; the verified-mapping gate is already part of the decision and cannot be left out.

## Addendum (issue 138): manual dispatch

[ADR 0028](0028-manual-dispatch-claim-and-last-moment-send-gate.md) implements the send route. Step 5b runs in `SendPolicyGate` under the customer and message locks, in the same transaction that commits T6, T7 and the `STARTED` attempt row, and the provider call follows that commit. The send command carries the parameter values and `expectedSender` of the binding whose fingerprint just matched. The §5 abuse bounds are `dokene.messaging.min-contact-interval` (`CONTACT_FREQUENCY_EXCEEDED`) and `dokene.messaging.send-rate-per-tenant-per-minute` (`429 RATE_LIMITED`). The send stays in the member request context (§4). The bounded recovery processor that ADR 0028 §5 adds marks expired claims as unknown outcomes under the authorizing member's re-validated identity and never calls the provider. ADR 0023's "no scheduler" note no longer holds for that recovery path only. T1 to T6 authorize against the membership as it is under the customer lock (`CurrentMembershipAuthorizer`), not as the request resolved it.
