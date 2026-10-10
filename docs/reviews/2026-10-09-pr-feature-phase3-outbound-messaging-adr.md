# PR description, feature/phase3-outbound-messaging-adr, 2026-10-09

Written by `/document pr` from the branch diff against `origin/main`. The review this PR answers is [2026-10-09-feature-phase3-outbound-messaging-adr.md](2026-10-09-feature-phase3-outbound-messaging-adr.md).

## Title

[Issue-133] [Messaging][Phase 3] Add the message data model and state machine (spec 0001)

## Body

## What

Adds the `messaging` module that every later Phase 3 feature builds on: six forced RLS tables, an immutable `OutboundMessage` aggregate that implements all sixteen ADR 0023 transitions, transactional submit, approve, reject and cancel services, and the delivery status sink. It stops before HTTP. Feature 2 adds the routes, feature 3 the provider call, feature 8 the webhook.

## Why

Implements `docs/specs/0001-message-data-model-and-state-machine/index.md` under ADR 0023 (`docs/adr/0023-phase-3-outbound-messaging-contracts.md`), which this branch also adds. The data model is the costliest thing to redo and nine later features sit on these tables, so the full state machine, the ports later features implement, the audit extension and the follow up cadence anchor all land in this one slice rather than piecemeal.

The 2026-10-09 amendment (AC-13 and AC-14) came out of the fresh model review in `docs/reviews/2026-10-09-feature-phase3-outbound-messaging-adr.md`. The original contact foreign key with `ON DELETE RESTRICT` would have made any messaged phone number permanent, and a provider supplied report time drove `sent_at` and the cadence anchor with no bound.

## Changes

**Schema (V16, V17)**
- `V16__create_outbound_messaging.sql`: `outbound_messages`, `outbound_message_approvals`, `outbound_message_cancellations`, `outbound_send_attempts`, `outbound_message_events` (append only, `SELECT, INSERT` grant) and `outbound_message_idempotency_keys`. Every table has `tenant_id`, forced RLS, per command policies and DML only grants. The partial unique index `one_open_message_per_customer` enforces one open message per customer. An identity trigger refuses updates to body, recipient, action, intent, locale, origin, policy version and send key. `customer_follow_up_policies` gains `last_outbound_message_date`.
- `contact_id` is a snapshot with no foreign key to `customer_phone_contacts` (amendment). Ownership is proven by the service under the customer lock at submit and approve, so the customer module can still delete a contact row when an operator corrects a phone.
- `V17__extend_audit_for_outbound_messaging.sql`: five nullable columns on `audit_events`, shape constraints for the twelve Phase 3 event types, a provider attributed shape (tenant only, no actor or membership) allowed solely for `MESSAGE_DELIVERY_UPDATED`, and `append_audit_event` recreated with defaulted trailing parameters so existing callers keep resolving.

**Domain (`messaging.domain`, no Spring, no JDBC)**
- `OutboundMessage` record with pure transition methods for T1 to T16. Each returns a `Transition` (next record plus event) or throws `MessagingRefusedException` with `INVALID_TRANSITION`. Delivery reports never throw; cells outside the report table return `Ignored` with a non applied event and a version bump.
- Report times are bounded to `[createdAt, now]` before they touch `sent_at`, `delivered_at`, `read_at` or the cadence anchor (amendment).
- Closed `MessagingErrorCode` enum declared in full now so features 2, 3 and 10 map to problem+json from one set. `RequestFingerprint` for idempotency replay equality.

**Application**
- `OutboundMessageCommandService`: lock order is advisory idempotency lock, then customer row, then message row. Guards for T1 and T2 run at execution time (customer active, no do not contact, consent on the exact contact and channel, follow up `DUE` or `OVERDUE`, policy version, body safety, template mapping, no open message). Approve additionally refuses `CONTACT_NOT_OWNED` when the contact was removed after submit (amendment).
- Submit, approve, reject and cancel refuse U+0000 in the body and in notes with `INVALID_INPUT` before any lock, as the customer module does for names and notes, instead of failing at the Postgres insert (Codex review).
- Idempotency replay returns the stored message plus child record with `created=false` and writes nothing. Same key with a different fingerprint is refused with `IDEMPOTENCY_KEY_REUSED`.
- `DefaultDeliveryStatusSink`: looks up by `(tenant, providerMessageId)`, refuses without a `ProviderContext` or with a member context, calls the follow up touch recorder when a report lands on `SENT`.
- Ports for later features: `MessagingProvider`, `DeliveryStatusSink`, `TemplateMappingGate` with a fail closed stub driven by `dokene.messaging.template-gate.stub.enabled-intents`.

**Cross module**
- `audit`: `DurableMessageAuditAdapter`, the provider audit capability in `TransactionalAuditRecorder`, new event types and metadata.
- `followup`: `FollowUpTouchRecorder.recordOutboundMessage` sets the tenant local date anchor and clears snooze and explicit next date. The repository upsert uses `GREATEST` so the anchor never moves backwards (amendment). The evaluator treats `LAST_OUTBOUND_MESSAGE` as a fourth cadence anchor. `configureCustomer` now takes the customer row lock like the other policy writers, so a policy change cannot overtake a messaging command that evaluated the policy under that lock (Codex review).
- `tenant`: `ProviderContext` and `ScopedValueProviderContextProvider`, plus the signer's provider capability.
- `ModuleDependencyArchitectureTest` (ArchUnit): upstream modules may not depend on `messaging`, and `messaging.domain` may not depend on Spring or JDBC. A fixture proves the rule actually fires.

**Docs and housekeeping**
- ADR 0023, spec 0001 (index, rationale, verify), the scope under `docs/scope/`, the fresh model review, wiki pages for messaging and architecture. Security invariant 24 is narrowed to what the code does: T1, T2 and T6 re-check the guards, T3 to T5 check only state and version, and T7 plus non applied reports write an event row but no audit row.
- `.gitignore` rules for local tooling (`.pnpm-store/`, `compose.override.yaml`, `frontend/pnpm-lock.yaml`). `CLAUDE.md` is now a thin pointer to `AGENTS.md`. ArchUnit added to the test classpath.

## How to test / verify

All steps run against the Testcontainers Postgres the backend tests start. Docker must be up.

- `cd backend && ./gradlew test --tests 'io.github.stevdrey.dokene.messaging.domain.*'` proves the exhaustive transition matrix (ten states, both `outcomeUnknown` values, every command) and the delivery report matrix including the bounded report times (AC-3, AC-14).
- `./gradlew test --tests 'io.github.stevdrey.dokene.tenant.security.OutboundMessagingIntegrationTest'` drives submit, replay, key reuse, second open message, approve, send, delivery, late report, event sequence equals version, provider attributed audit, cadence anchor, log hygiene, audit rollback, a four writer concurrent submit, the phone correction path, and the schema's two foreign keys (AC-2, AC-4 to AC-10, AC-13, AC-14).
- `./gradlew test --tests 'io.github.stevdrey.dokene.tenant.security.OutboundMessagingGuardIntegrationTest'` proves archived, do not contact, missing consent and not due customers are refused at submit and again at approve after the change (AC-4).
- `./gradlew test --tests 'io.github.stevdrey.dokene.tenant.security.TenantIsolationSecurityIntegrationTest' --tests 'io.github.stevdrey.dokene.tenant.persistence.jpa.TenantPersistenceIntegrationTest'` checks RLS, policies and grants on all six tables and cross tenant reads (AC-1, AC-8).
- `./gradlew test --tests 'io.github.stevdrey.dokene.architecture.ModuleDependencyArchitectureTest'` for AC-11, `./gradlew test --tests 'io.github.stevdrey.dokene.followup.*'` for AC-9.
- The full checklist with expected outcomes per step is in `docs/specs/0001-message-data-model-and-state-machine/verify.md`.
- **CI on `79c601d`:** `test`, `dependency-check`, CodeQL (Java and TypeScript), Gitleaks and Trivy all passed. An earlier run failed on one assertion that compared a nanosecond instant with the microsecond value Postgres stores; fixed in `73ecf4e`.

## Risk & rollout

Two migrations. V16 is purely additive. V17 drops and recreates `dokene.append_audit_event` with the new parameters defaulted to null, so every existing positional caller keeps working, and the new constraints validate against existing rows because the new columns are null everywhere. No feature flags. No HTTP surface, so nothing is reachable from the browser yet. Rollback is a reverse migration of V17 and V16, which drop empty tables at this point.

Accepted residual risks, recorded in the spec and scope:
- ADR 0023 counts `SENT` and `DELIVERED` as open, so a message that never gets a delivered or read report blocks that customer forever. Feature 11 in the scope owns the ADR amendment and must land before feature 7 sends to real customers.
- A pending or approved message whose contact was removed keeps the one open slot until an operator rejects or cancels it. Feature 2 must expose a derived `contactRemoved` flag so the operator can see why.
- The provider audit path checks only that no member context is bound. The review suggested also matching `ProviderContextProvider.current()` against the tenant; that is not done here.
- One verify.md step was not exercised: a report under tenant B's provider context for tenant A's provider id returning `NotFound`.

## Notes for reviewers

- The contact snapshot decision (drop the foreign key rather than soft retire contacts) is argued in `rationale.md` under "Contact lifecycle". Soft retiring contacts is the better long term shape and is queued as its own feature, so please review this as the smallest change that keeps the customer module's existing delete behaviour.
- The report time bound lives in the domain method, not at the webhook edge, so feature 8 cannot forget it and feature 3's local provider is covered too. The touch recorder's `GREATEST` is a second, independent guard because dispositions and purchases also write that anchor.
- Lock order is the same in the command service and the sink. Worth a look that nothing acquires the message lock before the customer lock.
- `CommandResult.record` is `Optional<Object>`. A sealed record type would be cleaner for feature 2; left as is to keep this slice small.
- Issue #133 still describes message "revisions". ADR 0023 replaced that with an immutable message whose approval binds the exact stored body and version, which this PR implements. The issue's retention protections for message bodies are not in spec 0001 and remain open, so this PR does not close it.

Part of #133 (work item A2 of #130).
