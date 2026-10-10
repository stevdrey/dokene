# 0001. Message data model and state machine

**Date**: 2026-10-08
**Amended**: 2026-10-09 (contact lifecycle and report time bound, from the 2026-10-09 review)
**Status**: Accepted

## Summary

This spec turns the message lifecycle fixed by ADR 0023 into concrete tables, Java domain types, transactional command services and tests inside a new `messaging` module. An outbound message is an immutable record (its text and recipient never change after submission) that moves through ten states, and every move is a named command checked by domain code, locked at the database, and written to the audit log in the same transaction. The slice stops before HTTP: feature 2 adds the routes on top of the services defined here, feature 3 adds the provider call, and feature 8 adds the webhook that calls the delivery sink defined here.

## Requirements

**User stories**:
- As an operator, I want a draft I submit to be frozen as exactly the text and recipient that will be approved and sent, so that nobody can approve one thing and send another.
- As an approver, I want approve, reject and cancel to be refused when the message is not in the state I saw, so that two people acting at once cannot both win.
- As a tenant admin, I want every message transition in the audit log with no message body, phone number or note in it, so that the trail is complete and safe to read.
- As a builder of features 2, 3 and 8, I want fixed ports and services to code against, so that routes, the send pipeline and webhooks slot in without reshaping the data.

**Acceptance criteria**:
- **AC-1**: Migrations `V16` and `V17` apply on an empty database and on a database at `V15`. Every new table has `tenant_id` with a foreign key to `dokene.tenants`, RLS enabled and forced, per command policies on `dokene.current_verified_tenant_id()`, a migration policy, and runtime grants limited to DML; `outbound_message_events` grants the runtime role `SELECT` and `INSERT` only.
- **AC-2**: With one message in any non terminal state for a customer, a second submit for that customer is refused with `MESSAGE_ALREADY_OPEN`, both by the service check and by the `one_open_message_per_customer` index when the service check is bypassed. Once that message is `READ`, `REJECTED`, `CANCELLED` or `FAILED`, a new submit succeeds.
- **AC-3**: The `OutboundMessage` domain type accepts exactly the (state, command) pairs in ADR 0023 §2 T1 to T12 for operator and system commands and refuses every other pair with `INVALID_TRANSITION`, proven by one exhaustive unit test over all ten states, both `outcomeUnknown` values and every command. Delivery reports (T13 to T16) never throw: every (state, report) cell outside the report table in Feature design yields `Ignored`, proven by a second exhaustive test over ten states and four report statuses. T10 goes to `FAILED` instead of `APPROVED` when the attempt count reaches the maximum passed in. T11 sets `outcomeUnknown`, T12 and T13 are only accepted while it is set, and leaving `SENDING` clears it.
- **AC-4**: Submit, approve, reject and cancel run as transactional services that take the advisory idempotency lock, then the customer row, then the message row, re check every guard in the numbered T1 and T2 guard lists in Feature design at execution time, refuse a stale `If-Match` version with `STALE_VERSION`, refuse malformed input with `INVALID_INPUT` before any lock, and require `MESSAGE_DRAFT` or `MESSAGE_APPROVE` through `TenantAuthorizationService`, auditing a denial as `AUTHORIZATION_DENIED` in a row that survives the refused command's rollback.
- **AC-5**: A replay with the same tenant, operation, key and request fingerprint returns the current message plus the child record the first request created, with `created=false`, performs no mutation and writes no audit row. The same key with a different fingerprint or a different target is refused with `IDEMPOTENCY_KEY_REUSED`. Two concurrent first requests with the same key produce exactly one record.
- **AC-6**: Every accepted transition writes its ADR 0023 §4.5 audit event in the same transaction, with metadata limited to target `MESSAGE`, `status_from`, `status_to`, optional `failure_category` and optional `attempt_number`. When the audit append fails, the transition is rolled back. `MESSAGE_DELIVERY_UPDATED` is written with tenant attribution and no actor or membership.
- **AC-7**: `DeliveryStatusSink` looks a message up by `(tenant, providerMessageId)` only, applies T13 to T16 under the customer then message locks, records an out of order or duplicate report as a non applied event row with a version bump and returns `Ignored`, returns `NotFound` as a value for an unknown id, calls the follow up touch recorder when T13 lands on `SENT`, and refuses to run unless a `ProviderContext` and no member `TenantContext` is bound.
- **AC-8**: A member of tenant B cannot read, lock, update or replay against a message of tenant A through the services, and direct runtime SQL under tenant B's context returns no tenant A rows from any of the six tables.
- **AC-9**: `FollowUpTouchRecorder.recordOutboundMessage` sets `last_outbound_message_date` to the tenant local date of the sent instant, clears `snoozed_until` and `explicit_next_date`, advances the policy `version`, writes no audit row of its own, and the evaluator treats the latest of the four anchors as the cadence anchor with timing source `LAST_OUTBOUND_MESSAGE`, so a customer sent a message today is no longer `DUE`.
- **AC-10**: Event rows for a message have contiguous `sequence_number` values starting at 1 and equal to the message `version` after the row was written, the right `actor_kind` per transition, and neither events, audit metadata nor log output ever contain `body`, `recipient_phone`, a note or `provider_message_id`.
- **AC-11**: An ArchUnit test fails the build when `followup`, `customer`, `purchase`, `ai`, `tenant`, `audit.domain` or `audit.persistence` depends on `messaging`, when `messaging` depends on `integration`, or when `messaging.domain` depends on Spring, JDBC or any other module except `ai.domain`. `audit.application` may depend on `messaging.application` for the durable adapter, as it already does for customer, purchase and AI.
- **AC-12**: With no `dokene.messaging.template-gate.stub.enabled-intents` configured, every `templateIntent` is `NOT_MAPPED` and submit is refused with `TEMPLATE_NOT_MAPPED`; listing an intent in that property makes it `MAPPED`.
- **AC-13**: `outbound_messages` has no foreign key to `customer_phone_contacts`. With a message in any state for a customer, editing that customer to replace the messaged phone with a different E.164 number succeeds and deletes the old contact row. A message still `PENDING_APPROVAL` for a removed contact is refused at approve with `CONTACT_NOT_OWNED` by T2 guard step 4 (the customer is active with no do not contact flag, so no earlier guard fires), writes no event or audit row, and keeps its `contact_id` and `recipient_phone` unchanged. An `APPROVED` message for a removed contact stays `APPROVED` until it is cancelled (T5) or the send guards refuse it. Submit still proves that the contact belongs to the customer under the customer lock (`NOT_FOUND`).
- **AC-14**: The instant a delivery report contributes to `sent_at`, `delivered_at`, `read_at` and to `FollowUpTouchRecorder` is the report's `occurredAt` bounded to the window `[message.createdAt, now]`, where `now` is the clock instant the caller passes in. A future report time becomes `now`; a report time earlier than the message's creation becomes `createdAt`; a value inside the window is kept as given. `FollowUpTouchRecorder.recordOutboundMessage` never moves `last_outbound_message_date` to an earlier date than the one already stored. The integration test fixes the `Clock` bean at a mid day instant in the tenant zone so the asserted dates cannot flip at midnight.

## Decision

**Chosen option**: Option 1: Immutable record aggregate with transition methods and command services.

The `messaging` module ships its six tables, an immutable `OutboundMessage` record whose methods implement T1 to T16, transactional services for T1 to T5 and the delivery sink for T13 to T16, the three ports later features implement, the audit extension for all twelve Phase 3 event types including the no actor provider shape, the follow up cadence anchor, a fail closed template gate stand in, and an ArchUnit dependency test.

**Implementation skills**: `backend-java-spring` (`.agents/skills/backend-java-spring/`) · `agent-task-workflow` (`.agents/skills/agent-task-workflow/`)

## Rationale

Reasoning, context and the options weighed: see [rationale.md](rationale.md).

## Feature design

**Module layout**: `io.github.stevdrey.dokene.messaging` with `domain`, `application`, `persistence.jdbc` packages, mirroring `followup`. No `api` package in this slice.

**Data model sketch**:

Every table below also has `id UUID PRIMARY KEY`, `tenant_id UUID NOT NULL REFERENCES dokene.tenants(id) ON DELETE RESTRICT`, RLS enabled and forced, `<table>_select_policy`, `_insert_policy`, `_update_policy`, `_delete_policy` for `dokene_runtime` on `tenant_id = dokene.current_verified_tenant_id()`, a `<table>_migration_policy` for `dokene_migration`, `REVOKE ALL FROM PUBLIC` and a runtime grant. Child tables reference the message with `FOREIGN KEY (tenant_id, message_id) REFERENCES dokene.outbound_messages(tenant_id, id) ON DELETE RESTRICT`, so `outbound_messages` needs `UNIQUE (tenant_id, id)`. Closed values are `VARCHAR` with a `CHECK (col IN (...))` constraint.

`outbound_messages`

| Column | Type | Constraint |
|---|---|---|
| customer_id | UUID NOT NULL | FK `(tenant_id, customer_id)` to `customers(tenant_id, id)` RESTRICT |
| contact_id | UUID NOT NULL | historical id of the contact row chosen at submit; no foreign key (see the contact lifecycle rule below) |
| recipient_phone | VARCHAR(20) NOT NULL | E.164 snapshot, `CHECK (recipient_phone ~ '^\+[1-9][0-9]{7,14}$')` |
| channel | VARCHAR(16) NOT NULL | `WHATSAPP` |
| status | VARCHAR(24) NOT NULL | the ten states |
| outcome_unknown | BOOLEAN NOT NULL DEFAULT false | `CHECK (NOT outcome_unknown OR status = 'SENDING')` |
| action | VARCHAR(40) NOT NULL | `SemanticAction` values |
| template_intent | VARCHAR(40) NOT NULL | `SemanticTemplateIntent` values |
| locale | VARCHAR(16) NOT NULL | BCP 47 tag, `es-419` today |
| origin | VARCHAR(16) NOT NULL | `AI_DRAFT`, `MANUAL` |
| body | VARCHAR(1000) NOT NULL | immutable after insert |
| source_policy_version | BIGINT NOT NULL | `CHECK (>= 0)` |
| send_key | UUID NOT NULL | `UNIQUE` |
| attempt_count | INT NOT NULL DEFAULT 0 | `CHECK (>= 0)` |
| provider_message_id | VARCHAR(128) NULL | partial `UNIQUE (tenant_id, provider_message_id) WHERE NOT NULL` |
| failure_category | VARCHAR(32) NULL | the seven categories; `CHECK (failure_category IS NULL OR status = 'FAILED')` |
| sent_at, delivered_at, read_at | TIMESTAMPTZ NULL | |
| created_by_membership_id, created_by_actor_id | UUID NOT NULL | |
| created_at, updated_at | TIMESTAMPTZ NOT NULL | |
| version | BIGINT NOT NULL DEFAULT 0 | `CHECK (>= 0)` |

Indexes: `one_open_message_per_customer UNIQUE (tenant_id, customer_id) WHERE status NOT IN ('READ','REJECTED','CANCELLED','FAILED')`; `(tenant_id, customer_id, sent_at)`; `(tenant_id, status, created_at DESC, id DESC)`; `(tenant_id, created_at DESC, id DESC)` for the unfiltered list. A trigger `prevent_outbound_message_identity_change`, in the style of V9's purchase trigger, raises when an `UPDATE` changes `customer_id`, `contact_id`, `recipient_phone`, `body`, `action`, `template_intent`, `locale`, `origin`, `source_policy_version` or `send_key`.

**Contact lifecycle rule** (amended 2026-10-09): a message records the contact it was submitted to as a snapshot, `contact_id` plus `recipient_phone`, and never as a live reference. There is no foreign key from `outbound_messages` to `customer_phone_contacts`, so the customer module keeps deleting a contact row when an operator removes or corrects a phone, exactly as it did before Phase 3. Ownership of the contact is proven by the service at submit time (T1 guard step 2, under the customer lock), which is the only moment the link has to be live. After that the message is history: `contact_id` may point at a row that no longer exists, and that is correct, because the message really was sent (or submitted) to that number. A pending message whose contact was removed cannot advance: T2 guard step 4 checks that the loaded customer still owns `message.contactId()` and refuses with `CONTACT_NOT_OWNED`; an `APPROVED` message stays `APPROVED` until an approver cancels it (T5) or feature 3's send guards (T6) refuse it with the same code, and those guards must run in the same transaction and under the same customer lock as the attempt insert (T7), so a contact cannot disappear between the check and the provider call. Operators cancel and resubmit to the corrected number, which is the trade off the spec already accepted; re adding the same number later creates a new contact row with a new id and no consent, so consent must be granted again before a new submit. Until it is cancelled, the stranded pending or approved message still holds the one open message per customer slot and refuses new submits with `MESSAGE_ALREADY_OPEN`; this is an accepted gap for this slice, and feature 2 must expose a derived `contactRemoved` flag on message list and detail (true when no `customer_phone_contacts` row with the message's `(tenant_id, customer_id, contact_id)` exists) so the operator can see why and cancel. Readers always display `recipient_phone`; a live contact row never carries a different number because the customer repository replaces a number by delete plus insert, never by an update in place.

`outbound_message_approvals` (1:1): `message_id UUID NOT NULL`, `UNIQUE (tenant_id, message_id)`; `decision VARCHAR(8)` in `APPROVE`, `REJECT`; `note VARCHAR(500) NULL`; `decided_at TIMESTAMPTZ NOT NULL`; `decided_by_membership_id`, `decided_by_actor_id UUID NOT NULL`.

`outbound_message_cancellations` (1:1): `message_id`, `UNIQUE (tenant_id, message_id)`; `status_from VARCHAR(24)` in `PENDING_APPROVAL`, `APPROVED`; `note VARCHAR(500) NULL`; `cancelled_at`; `cancelled_by_membership_id`, `cancelled_by_actor_id`.

`outbound_send_attempts` (1:N): `message_id`; `attempt_number INT NOT NULL CHECK (>= 1)`, `UNIQUE (tenant_id, message_id, attempt_number)`; `send_key UUID NOT NULL`; `outcome VARCHAR(24)` in `STARTED`, `ACCEPTED`, `FAILED_PERMANENT`, `FAILED_TRANSIENT`, `OUTCOME_UNKNOWN`; `failure_category VARCHAR(32) NULL`; `provider_message_id VARCHAR(128) NULL`; `started_at NOT NULL`; `finished_at NULL`; `requested_by_membership_id`, `requested_by_actor_id NOT NULL`. Checks: `(finished_at IS NULL) = (outcome = 'STARTED')`; `(failure_category IS NOT NULL) = (outcome IN ('FAILED_PERMANENT','FAILED_TRANSIENT'))`; `provider_message_id IS NULL OR outcome IN ('ACCEPTED','OUTCOME_UNKNOWN')` (an unknown outcome may still carry the id the provider returned before the connection dropped, which is what makes T13 reachable).

`outbound_message_events` (1:N, append only): `message_id`; `sequence_number INT NOT NULL CHECK (>= 1)`, `UNIQUE (tenant_id, message_id, sequence_number)`; `event_type VARCHAR(40)` in `SUBMITTED`, `APPROVED`, `REJECTED`, `CANCELLED`, `SEND_REQUESTED`, `SEND_ATTEMPT_STARTED`, `SENT`, `SEND_FAILED`, `SEND_OUTCOME_UNKNOWN`, `DELIVERY_UPDATED`; `status_from VARCHAR(24) NULL` (null only for `SUBMITTED`); `status_to VARCHAR(24) NOT NULL`; `applied BOOLEAN NOT NULL`; `actor_kind VARCHAR(8)` in `MEMBER`, `SYSTEM`, `PROVIDER`; `membership_id UUID NULL` with `CHECK ((actor_kind = 'MEMBER') = (membership_id IS NOT NULL))`; `failure_category VARCHAR(32) NULL`; `attempt_number INT NULL`; `occurred_at TIMESTAMPTZ NOT NULL`. `CHECK (applied OR status_from = status_to)`; `CHECK ((event_type = 'SUBMITTED') = (status_from IS NULL))`. Runtime grant: `SELECT, INSERT`. The `sequence_number` of a row equals the message `version` after the row's transaction, so no separate counter query is needed.

`outbound_message_idempotency_keys`: `operation VARCHAR(16)` in `SUBMIT`, `APPROVAL`, `CANCELLATION`, `SEND`, `RESOLUTION`; `idempotency_key VARCHAR(128) NOT NULL CHECK (~ '^[A-Za-z0-9._:-]{1,128}$')`; `UNIQUE (tenant_id, operation, idempotency_key)`; `request_fingerprint CHAR(64) NOT NULL`; `message_id UUID NOT NULL`; `record_id UUID NULL`; `created_at`. `record_id` is null for `SUBMIT` (the message is the record), the approval id for `APPROVAL` (approve and reject share the operation because `decision` is in the fingerprint), the cancellation id for `CANCELLATION`, the attempt id for `SEND` and the resolution's event id for `RESOLUTION`.

`customer_follow_up_policies` gains `last_outbound_message_date DATE NULL` (V16).

Audit (V17, additive like V13): `audit_events` gains `status_from VARCHAR(24)`, `status_to VARCHAR(24)`, `failure_category VARCHAR(32)`, `attempt_number INT`, `enabled BOOLEAN`, all nullable. `ck_audit_shape` gains one branch per Phase 3 event type: the nine `MESSAGE_*` types require `target_type = 'MESSAGE'`, `status_to NOT NULL`, `enabled IS NULL`; `TEMPLATE_MAPPING_UPDATED`, `INTEGRATION_UPDATED` and `OUTBOUND_KILL_SWITCH_CHANGED` require their target type and `enabled NOT NULL`. A new `ck_audit_message_columns` keeps the five columns null for every other event type. `ck_audit_attribution` becomes: all three null, or all three non null, or `tenant_id NOT NULL AND actor_id IS NULL AND membership_id IS NULL AND event_type = 'MESSAGE_DELIVERY_UPDATED'`. `ck_audit_permission` gains the message permissions it lacks. `dokene.append_audit_event` is dropped and recreated with the five new parameters appended as `DEFAULT NULL`, so every existing positional caller keeps resolving; `REVOKE` and `GRANT` are repeated for the new signature. The new constraints validate against existing rows because the new columns are null everywhere.

**Domain types** (`messaging.domain`, no Spring, no JDBC):

- Enums: `MessageStatus`, `MessageOrigin`, `MessageChannel`, `FailureCategory`, `SendAttemptOutcome`, `ApprovalDecision`, `MessageEventType`, `MessageActorKind`, `MessagingErrorCode`.
- `MessagingErrorCode` values, the closed set feature 2 maps to problem+json: `INVALID_INPUT`, `FORBIDDEN`, `NOT_FOUND`, `STALE_VERSION`, `IDEMPOTENCY_KEY_REUSED`, `INVALID_TRANSITION`, `CUSTOMER_ARCHIVED`, `DO_NOT_CONTACT`, `NO_CONTACT_CONSENT`, `CONTACT_NOT_OWNED`, `FOLLOW_UP_INELIGIBLE`, `MESSAGE_ALREADY_OPEN`, `CONTACT_FREQUENCY_EXCEEDED`, `TEMPLATE_NOT_MAPPED`, `BODY_REJECTED`, `OUTBOUND_DISABLED_GLOBALLY`, `OUTBOUND_DISABLED_FOR_TENANT`, `INTEGRATION_DISABLED`, `SEND_ATTEMPTS_EXHAUSTED`, `RATE_LIMITED`, `PROVIDER_UNAVAILABLE`. Codes this slice does not raise are declared now so the enum is complete for features 2, 3 and 10.
- `MessagingRefusedException extends RuntimeException` carrying one `MessagingErrorCode`; the only refusal type the module throws.
- Records: `OutboundMessage`, `MessageApproval`, `MessageCancellation`, `SendAttempt`, `MessageEvent`, `Transition(OutboundMessage next, MessageEvent event)`, `DeliveryOutcome` sealed with `Applied(Transition)`, `Ignored(MessageEvent)`. The domain uses raw `UUID` and `java.time` types only; the application layer maps `MessageEventType` to `AuditEventType` and wraps ids in module types where needed.
- `ProviderSendResult` and `DeliveryStatus` also live in `messaging.domain` because transitions take them as input; the ports in `messaging.application` reuse them.
- `OutboundMessage` methods, each pure, each returning a `Transition` or throwing `INVALID_TRANSITION`: `static submit(...)` (T1), `approve(by, now)` (T2), `reject(by, now)` (T3), `cancel(by, now)` (T4 or T5 by current state), `requestSend(by, now)` (T6), `startAttempt(now)` (T7, increments `attemptCount`), `completeAttempt(ProviderSendResult, now, int maxSendAttempts)` (T8 to T11; `OutcomeUnknown` stores its optional provider id), `resolveFailed(by, now)` (T12, requires `outcomeUnknown`), `applyDeliveryReport(DeliveryStatusReport, now)` (T13 to T16, returns `DeliveryOutcome`, never throws). Leaving `SENDING` by any path clears `outcomeUnknown`. Also `isOpen()`, `isTerminal()`, `nextVersion()`.

**Delivery report table** (rows are the current state, columns the report status; `A` applies the transition named, `I` records a non applied event and returns `Ignored`):

| State | `SENT` report | `DELIVERED` report | `READ` report | `FAILED` report |
|---|---|---|---|---|
| `PENDING_APPROVAL`, `APPROVED`, `QUEUED`, `REJECTED`, `CANCELLED`, `FAILED` | I | I | I | I |
| `SENDING`, `outcomeUnknown` false | I | I | I | I |
| `SENDING`, `outcomeUnknown` true | A T13 to `SENT` | A T13 to `DELIVERED` | A T13 to `READ` | A T13 to `FAILED` |
| `SENT` | I | A T14 | A T15 | A T16 |
| `DELIVERED` | I | I | A T15 | I |
| `READ` | I | I | I | I |

T13 to `DELIVERED` or `READ` also sets `sent_at` to the report time when it is null, and calls the touch recorder, because the message was in fact sent. **Report time bound** (amended 2026-10-09): the instant used for `sent_at`, `delivered_at`, `read_at` and the touch recorder is `report.occurredAt` bounded to `[createdAt, now]`, where `now` is the argument every caller passes to the domain method from the injected clock (`messaging.domain` has no clock of its own; `DefaultDeliveryStatusSink`, `OutboundMessageTestDriver` and feature 3's callers all pass `clock.instant()`). A provider cannot say a thing happened in the future, nor before the message existed; either value would move `last_outbound_message_date` and misplace the customer in the due queue. Values inside the window are trusted as given, because a late report describing an earlier event is the normal case. Event `occurred_at` and attempt timestamps need no bound: they already come from the clock, not from the report.

**T1 guard order** (service, after input validation and the locks, failing on the first miss with the code shown): 1 customer exists in the tenant (`NOT_FOUND`); 2 contact exists and belongs to the customer (`NOT_FOUND`, the ADR's 404 rule); 3 customer `ACTIVE` (`CUSTOMER_ARCHIVED`); 4 no do not contact (`DO_NOT_CONTACT`); 5 consent for `(contact, WHATSAPP)` is `GRANTED` (`NO_CONTACT_CONSENT`); 6 fresh evaluation is `DUE` or `OVERDUE` (`FOLLOW_UP_INELIGIBLE`); 7 `If-Match` equals the policy version from that evaluation (`STALE_VERSION`); 8 body passes the safety validator (`BODY_REJECTED`); 9 template gate returns `MAPPED` (`TEMPLATE_NOT_MAPPED` for both `NOT_MAPPED` and `DISABLED`); 10 no open message for the customer (`MESSAGE_ALREADY_OPEN`). Tenant and membership activity are checked by `TenantAuthorizationService` before step 1 (`FORBIDDEN`).

**T2 guard order**: 1 message exists in the tenant (`NOT_FOUND`); 2 `If-Match` equals the message version (`STALE_VERSION`); 3 state is `PENDING_APPROVAL` (`INVALID_TRANSITION`); 4 the customer still owns `message.contactId()`, checked against the phones loaded with the locked customer (`CONTACT_NOT_OWNED`, amended 2026-10-09); then T1 steps 3 to 6 and 8 to 9 unchanged, and step 10 as "no other open message for the customer, excluding this one". T3, T4 and T5 run only steps 1 to 3 with the state the transition expects.

**Input validation** (`INVALID_INPUT`, before the idempotency lock): body null, blank or over 1000 characters; note over 500 characters; `action`, `templateIntent`, `origin` not in their enums; `locale` not in the allowed set, which is `es-419` only in Phase 3 (a constant `SUPPORTED_LOCALES` in `messaging.domain`); idempotency key not matching the ADR grammar; `If-Match` missing or not a non negative integer.

**Application ports** (`messaging.application`):

- `MessagingProvider`: `ProviderSendResult send(OutboundSendCommand command)`. `OutboundSendCommand(recipientPhone, providerTemplateName, language, List<String> parameters, locale, UUID sendKey)`. `ProviderSendResult` sealed: `Accepted(String providerMessageId)`, `RejectedPermanently(FailureCategory)`, `FailedTransiently(FailureCategory)`, `OutcomeUnknown(Optional<String> providerMessageId)`. No implementation in this slice; feature 3 adds the local adapter.
- `DeliveryStatusSink`: `DeliveryStatusResult apply(DeliveryStatusReport report)`. `DeliveryStatusReport(String providerMessageId, DeliveryStatus status, Instant occurredAt, Optional<FailureCategory> failure)`; `DeliveryStatus` enum `SENT`, `DELIVERED`, `READ`, `FAILED`. A `FAILED` report with no category stores `UNKNOWN`. `DeliveryStatusResult` sealed: `Applied(MessageStatus from, MessageStatus to)`, `Ignored`, `NotFound`. Implemented here by `DefaultDeliveryStatusSink`. Duplicate suppression by provider event id is feature 8's job in `provider_webhook_events`; the sink only guarantees that a repeated or late status never moves state backwards.
- `ProviderContext(TenantId tenantId)`: a new trusted context type in `tenant.application`, bound by the webhook boundary through a `ProviderContextProvider` scoped value (feature 8 binds it; this slice defines it and a test binder). It is distinct from `TenantContext`, which requires a membership. The RLS tenant context is set from its tenant id exactly as `callWithTenantId` does today.
- `TemplateMappingGate`: `TemplateMappingStatus check(SemanticTemplateIntent intent)`, values `MAPPED`, `NOT_MAPPED`, `DISABLED`. Implemented here by `StubTemplateMappingGate` driven by `MessagingTemplateGateStubProperties`; feature 6 replaces the bean.
- `OutboundMessageRepository`: `insert`, `findById`, `findByIdForUpdate`, `findIdsByProviderMessageId` (returns message id and customer id, no lock), `findOpenByCustomer(customerId, Optional<UUID> excludingMessageId)`, `update(OutboundMessage expected, OutboundMessage next)` (predicate on `version`), `insertApproval`, `insertCancellation`, `insertAttempt`, `updateAttempt`, `appendEvent`, `findEvents(messageId)`. `insert` translates SQLState `23505` on constraint `one_open_message_per_customer` to `MESSAGE_ALREADY_OPEN` and rethrows any other integrity violation.
- `MessageIdempotencyStore`: `acquireLock(tenantId, operation, key)` (`pg_advisory_xact_lock(hashtext(tenant||':'||operation), hashtext(key))`), `find(tenantId, operation, key)`, `record(...)`.
- `MessageAuditPort` with one method per event type, implemented in `audit.application` as `DurableMessageAuditAdapter` like the existing durable adapters, plus a `deliveryUpdated(ProviderContext, ...)` method that the recorder writes with tenant only attribution. Denials go through the existing `AuthorizationDeniedEvent` route, which uses `REQUIRES_NEW` propagation so the row survives the refused command's rollback.
- `FollowUpTouchRecorder` lives in `followup.application`: `recordOutboundMessage(CustomerId customerId, Instant sentAt)`. It computes the tenant local date from the tenant follow up policy zone, updates the customer policy row (creating it from the tenant default when missing, as dispositions do), clears `snoozed_until` and `explicit_next_date`, increments `version`, writes no audit row (`MESSAGE_SENT` is the record), and runs with mandatory propagation under the caller's customer lock.

**Command services** (`messaging.application`, `@Transactional`, constructor injected, take `Clock`):

- `OutboundMessageCommandService` with `submit(SubmitMessageCommand)`, `approve(ApprovalCommand)`, `reject(ApprovalCommand)`, `cancel(CancellationCommand)`. Each returns `CommandResult(OutboundMessage message, Optional<Object> record, boolean created)` where `record` is the approval or cancellation created. Order inside every method: validate input (`INVALID_INPUT`), require permission (for cancel, either `MESSAGE_DRAFT` or `MESSAGE_APPROVE` here, then the state specific one after the message lock, auditing the denial with that permission), acquire idempotency lock, look up key (replay returns the current message plus the stored child record with `created=false`, fingerprint or target mismatch refuses `IDEMPOTENCY_KEY_REUSED`), lock customer `FOR UPDATE`, lock message `FOR UPDATE` (not for submit), run the numbered guard list, call the domain method, persist next state and child record, append the event with `sequence_number` equal to the new version, record the idempotency key, write audit. The `CONTACT_FREQUENCY_EXCEEDED` and send policy guards are not run here; they belong to T6 in feature 3.
- `DefaultDeliveryStatusSink`: require a bound `ProviderContext` and no bound member `TenantContext` (refuse `FORBIDDEN` otherwise), resolve message and customer ids by provider message id without a lock (`NotFound` when absent), lock customer `FOR UPDATE`, lock message `FOR UPDATE`, re check the provider id still matches, call `applyDeliveryReport`, persist, append the event (`applied` true or false), bump the version in both cases, call `FollowUpTouchRecorder` when the applied transition lands on `SENT` (or on `DELIVERED` or `READ` from `SENDING`), audit only when applied.
- Test fixture `OutboundMessageTestDriver` (test scope only): drives `requestSend`, `startAttempt` and `completeAttempt` through the repository so integration tests can seed `SENT` and `SENDING` rows before feature 3 exists.

**State transitions**: as ADR 0023 §2 T1 to T16, unchanged. Terminal: `READ`, `REJECTED`, `CANCELLED`, `FAILED`. Operator terminal: `SENT`, `DELIVERED`. Guard inputs the domain method takes as parameters rather than reads itself: `maxSendAttempts` (T10), `now` (every transition), the provider result (T8 to T11), the delivery status (T13 to T16).

**API surface** (no HTTP in this slice; the surface is the Java contract feature 2 wraps):

| Operation | Method | Key inputs | Key outputs | Auth | Key errors |
|---|---|---|---|---|---|
| submit | `OutboundMessageCommandService.submit` | customerId, contactId, action, templateIntent, body, locale, origin, ifMatchPolicyVersion, idempotencyKey | message, created | `MESSAGE_DRAFT` | `STALE_VERSION`, `MESSAGE_ALREADY_OPEN`, `NO_CONTACT_CONSENT`, `TEMPLATE_NOT_MAPPED`, `BODY_REJECTED`, `IDEMPOTENCY_KEY_REUSED` |
| approve or reject | `.approve` / `.reject` | messageId, decision, note, ifMatchVersion, idempotencyKey | message, created | `MESSAGE_APPROVE` | `INVALID_TRANSITION`, `STALE_VERSION`, guards as T2 |
| cancel | `.cancel` | messageId, note, ifMatchVersion, idempotencyKey | message, created | `MESSAGE_DRAFT` from `PENDING_APPROVAL`, `MESSAGE_APPROVE` from `APPROVED` | `INVALID_TRANSITION`, `STALE_VERSION` |
| delivery report | `DeliveryStatusSink.apply` | providerMessageId, status, occurredAt, failure | `Applied`, `Ignored`, `NotFound` | trusted provider context, no membership | `FORBIDDEN` when a membership is present |
| template check | `TemplateMappingGate.check` | templateIntent | `MAPPED`, `NOT_MAPPED`, `DISABLED` | none (internal) | none |
| cadence touch | `FollowUpTouchRecorder.recordOutboundMessage` | customerId, tenantLocalDate | none | internal, called at T8 by feature 3 | none |

**Value sourcing**:

| Action | Value produced / displayed | Source |
|---|---|---|
| submit | `recipient_phone` | `customer_phone_contacts.phone_e164` for `contactId`, read under the customer lock, after checking the contact belongs to the customer |
| submit | consent and do not contact | `ContactPolicyRepository` for the customer, consent for `(contactId, WHATSAPP)` must be `GRANTED` |
| submit | follow up status `DUE` or `OVERDUE` | `FollowUpService.evaluateCustomer` at execution time |
| submit | `source_policy_version` | the `If-Match` input, which must equal `customer_follow_up_policies.version` returned by that evaluation |
| submit and approve | body safety | `DraftSafetyValidator.validate(MessageDraft, allowedContextText, grounding)` where the `MessageDraft` is built from body, action, intent and locale with empty rationale, evidence, warnings and variables; `allowedContextText` and `DraftGroundingContext` come from a new public `DraftGroundingAssembler.assemble(customerId)` extracted from `FollowUpDraftService` in `followup.application`, used identically for `AI_DRAFT` and `MANUAL` bodies |
| submit | template mapping status | `TemplateMappingGate.check(templateIntent)` |
| submit | `send_key` | `UUID.randomUUID()` at submit, never regenerated |
| submit | open message check | `findOpenByCustomer` under the customer lock, then the index as the backstop |
| every transition | `occurred_at`, `decided_at`, `cancelled_at`, timestamps | the injected `Clock` |
| every transition | `version` | `Math.incrementExact(current)` as `followup` does, overflow refuses with `STALE_VERSION` |
| every transition | `sequence_number` | the message `version` after the transition (version and sequence advance together, including on ignored reports) |
| every transition | actor attribution | `TenantContext.membershipId()` and `identityId()`; `SYSTEM` for T7 to T11, `PROVIDER` for the sink |
| idempotency | `request_fingerprint` | SHA-256 lowercase hex over a canonical byte string: `operation`, then each field in a fixed per operation order, each encoded as a 4 byte big endian length followed by UTF-8 bytes, with a null field encoded as length `0xFFFFFFFF` so null and empty differ. Fields: submit `customerId, contactId, action, templateIntent, body, locale, origin, ifMatch`; approval `messageId, decision, note, ifMatch`; cancellation `messageId, note, ifMatch`; send and resolution `messageId, ifMatch` (resolution adds `outcome, note`) |
| replay | the representation | the current message by stored `message_id`, plus the child record by stored `record_id` |
| T10 | `maxSendAttempts` | a method parameter; feature 3 supplies `dokene.messaging.max-send-attempts` |
| T8, T11 and T13 | `provider_message_id` | `ProviderSendResult.Accepted`, the optional id on `OutcomeUnknown`, or the report's `providerMessageId` |
| T13 to T16 | `sent_at`, `delivered_at`, `read_at` | the report's `occurredAt`, bounded to `[message.createdAt, now]` with `now` the caller's clock instant (AC-14) |
| T13 to T16 | event `occurred_at` | the injected `Clock` (when the report was applied, not when the provider says it happened) |
| T16 and T13 to `FAILED` | `failure_category` | the report's category, `UNKNOWN` when absent |
| cadence touch | tenant local date | computed inside `FollowUpTouchRecorder` as `sentAt.atZone(tenantFollowUpPolicy.zoneId()).toLocalDate()`, the evaluator's existing rule, where `sentAt` is the bounded value above |
| evaluator | anchor and timing source | latest of `last_manual_follow_up_date`, `last_dismissed_date`, `last_outbound_message_date`, last valid purchase date, with the existing "not before" tie rule; new `FollowUpTimingSource.LAST_OUTBOUND_MESSAGE` |
| sink | tenant | `TenantContext` established by the caller with `callWithTenantId`; never from the report |
| logs and metrics | allowed fields | message id, tenant id, status pair, error code, failure category, attempt number only |

**Key invariants**:
- `body`, `recipient_phone`, `contact_id`, `action`, `template_intent`, `locale`, `origin`, `source_policy_version` and `send_key` never change after insert (trigger plus no `UPDATE` path in the repository).
- At most one open message per `(tenant_id, customer_id)` (index plus service check).
- `attempt_count` equals the number of `outbound_send_attempts` rows for the message (asserted by tests, maintained by `startAttempt`).
- `outcome_unknown` is true only in `SENDING` and is cleared by every exit from `SENDING`; `failure_category` on the message is set only in `FAILED`; `provider_message_id` is set only by `ACCEPTED` or `OUTCOME_UNKNOWN` attempts or applied reports.
- No two transitions on one message interleave: every path, the sink included, takes the customer lock then the message lock, in that order, after the advisory idempotency lock when there is one.
- The follow up evaluation a command runs under the customer lock stays current until the command commits: every `customer_follow_up_policies` writer in `FollowUpService` (`configureCustomer`, `snooze`, `dismiss`, `recordManualFollowUp`) and the touch recorder's callers take the same customer lock first. The tenant policy (`configureTenant`) is not covered by the customer policy version and is outside this rule.
- `sequence_number` of the latest event equals the message `version`.
- Every applied transition writes exactly one event row and one audit row in its transaction; a non applied report writes one event row and no audit row.
- The event log, audit metadata and log lines contain none of `body`, `recipient_phone`, notes or `provider_message_id`.
- `messaging.domain` imports only `java.*` and `ai.domain`.
- `outbound_messages` never holds a foreign key to `customer_phone_contacts`; contact ownership is a service check at submit (T1 step 2), approve (T2 step 4) and send (T6), and a removed contact leaves the message's snapshot intact.
- No timestamp written from a delivery report, and no instant handed to the touch recorder, is later than the clock instant at which the report was applied or earlier than the message's `created_at`; `last_outbound_message_date` never moves to an earlier date.

**Security model**:
- Tenant scoping: every query runs under the signed tenant context; RLS is the backstop for all six tables (AC-8).
- `MESSAGE_DRAFT` for submit and cancel from `PENDING_APPROVAL`; `MESSAGE_APPROVE` for approve, reject and cancel from `APPROVED`; checked in the service through `TenantAuthorizationService`, denials audited as `AUTHORIZATION_DENIED` with the permission, in addition to feature 2's controller annotation.
- The delivery sink runs only under a `ProviderContext` with no member `TenantContext` bound and writes provider attributed audit rows; a member context is refused with `FORBIDDEN`.
- Denials on every command (submit, approve, reject, cancel) are audited; ADR 0023 requires T1, T2 and T6, and ADR 0005 already audits all denials, so this adds nothing new.
- Notes are readable with `MESSAGE_READ` on the message detail (feature 2) and never appear in events, audit or logs.
- Personal data: `recipient_phone` and `body` are tenant business data in `outbound_messages` only. They are not regulated data beyond the existing customer PII stance, and the same logging rules apply.

**Configuration required**:
- `dokene.messaging.template-gate.stub.enabled-intents`: comma separated `SemanticTemplateIntent` names the stub reports as `MAPPED`; empty by default so submit fails closed. Removed by feature 6.

**Critical test scenarios**:
- Happy path: submit, approve, then the test driver runs T6 to T8 with `Accepted`, then a `DELIVERED` report through the sink against Postgres; six tables populated, events 1 to 6 contiguous and equal to the version, audit rows for each applied transition, customer leaves the due queue after the touch recorder runs, verifies **AC-1**, **AC-6**, **AC-7**, **AC-9**, **AC-10**.
- Exhaustive command matrix: every `MessageStatus` crossed with every operator and system command and both `outcomeUnknown` values; expected cells from ADR 0023 §2 accept, all others throw `INVALID_TRANSITION`. Exhaustive report matrix: ten states by four report statuses match the delivery report table, verifies **AC-3**.
- Unknown outcome: `completeAttempt(OutcomeUnknown(id))` keeps `SENDING` with the flag and the id; a later `DELIVERED` report applies T13 and sets `sent_at`; `resolveFailed` after that is `INVALID_TRANSITION`, verifies **AC-3**, **AC-7**.
- Concurrency: two threads submit for the same customer with different keys, one succeeds and one gets `MESSAGE_ALREADY_OPEN`; two threads replay the same key, one row results; a direct insert bypassing the service hits the index, verifies **AC-2**, **AC-5**.
- Stale version: approve with `If-Match` one behind, refused `STALE_VERSION`, no event, no audit, verifies **AC-4**.
- Audit failure: the audit store throws, the submit transaction rolls back and no message row exists, verifies **AC-6**.
- Out of order: `READ` then `DELIVERED` report, state stays `READ`, a non applied event row exists, version advanced, result `Ignored`; unknown provider id returns `NotFound`; a member `TenantContext` is refused, verifies **AC-7**.
- Replay: the same key and fingerprint returns the same message and approval with `created=false` and no new audit row; the same key with a different note is refused `IDEMPOTENCY_KEY_REUSED`, verifies **AC-5**.
- Cross tenant: tenant B's context cannot see or lock tenant A's message through the repository or raw runtime SQL, verifies **AC-8**.
- Auth: a `VIEWER` membership calling submit receives `FORBIDDEN` and an `AUTHORIZATION_DENIED` audit row exists, verifies **AC-4**.
- Fail closed gate: no property, submit refused `TEMPLATE_NOT_MAPPED`, verifies **AC-12**.
- Architecture: an intentional `followup` to `messaging` import in a test fixture fails the ArchUnit rule, verifies **AC-11**.

## Build plan

Tracer Bullet: task 3 is the thin thread (one submit through migration, domain, repository, audit and RLS) and tasks 4 to 9 thicken each strand.

1. `V16__create_outbound_messaging.sql`: the six tables, indexes, trigger, policies, grants and the `last_outbound_message_date` column; extend `TenantIsolationSecurityIntegrationTest` and `TenantPersistenceIntegrationTest` to cover the six tables and the index, satisfies **AC-1**, **AC-2**, **AC-8**
2. `messaging.domain`: enums, records, `MessagingErrorCode`, `MessagingRefusedException`, `ProviderSendResult`, `DeliveryStatus`, `OutboundMessage` with all transition methods, the fingerprint helper, and both exhaustive matrix unit tests, satisfies **AC-3**
3. Thin thread for T1: `V17__extend_audit_for_outbound_messaging.sql` (all twelve event types, five columns, provider attribution branch, function recreated with defaulted parameters), `AuditEventType`, `AuditTarget.Type`, `AuditMetadata.MessageTransition` and `IntegrationToggle`, `DurableMessageAuditAdapter`, `DraftGroundingAssembler` extracted in `followup.application`, `JdbcOutboundMessageRepository` and `JdbcMessageIdempotencyStore`, `StubTemplateMappingGate` with its properties, and `OutboundMessageCommandService.submit` end to end against Postgres including input validation, replay, key mismatch, stale version, audit rollback, index race and permission denial, satisfies **AC-2**, **AC-4**, **AC-5**, **AC-6**, **AC-10**, **AC-12**
4. Approve, reject and cancel (T2 to T5) on the same service with their guard lists, two stage cancel permission, child records and tests, satisfies **AC-4**, **AC-5**, **AC-6**
5. Follow up anchor: `FollowUpTouchRecorder` port and implementation, `FollowUpTimingSource.LAST_OUTBOUND_MESSAGE`, `CustomerFollowUpPolicy` field, evaluator branch, repository mapping, queue SQL anchor, tests in the followup suite, satisfies **AC-9**
6. Ports and sink: `MessagingProvider`, `OutboundSendCommand`, `DeliveryStatusSink` types, `ProviderContext` and its provider in `tenant.application` with a test binder, `OutboundMessageTestDriver`, and `DefaultDeliveryStatusSink` with provider attributed audit, lock order, cadence touch on T13, out of order and not found tests, member context refusal, satisfies **AC-7**, **AC-6**, **AC-9**
7. Event read model: `findEvents(messageId)` ordered by `sequence_number`, the version equals sequence assertion, plus a log assertion test that the module's log statements never include the excluded fields, satisfies **AC-10**
8. ArchUnit: add `com.tngtech.archunit:archunit-junit5` to the backend test classpath and a `ModuleDependencyArchitectureTest` with the rules in AC-11, satisfies **AC-11**
9. Documentation touched by this slice: `docs/wiki/Messaging-and-Integrations.md` (retire `DRAFT`, record the tables), `docs/security/security-invariants.md` (the new invariants ADR 0023 lists), and a note in `docs/wiki/Architecture.md` on the arrow direction, satisfies **AC-1** (documentation of the shipped schema)
10. Amendment of 2026-10-09 (V16 and the slice are unreleased and uncommitted, so edit them in place rather than adding a migration): remove `fk_outbound_messages_contact` from `V16__create_outbound_messaging.sql` and the `customer_phone_contacts` reference in its comments, keep `contact_id NOT NULL`; add T2 guard step 4 in `OutboundMessageCommandService.decide` (approve branch only) checking the locked customer's phones for `message.contactId()` and refusing with `CONTACT_NOT_OWNED`; add an integration test that submits a message, edits the customer to replace the messaged phone with a different number, asserts the edit succeeds and the old contact row is gone, asserts the message's `contact_id` and `recipient_phone` are unchanged, then asserts approve is refused with `CONTACT_NOT_OWNED` and writes no event or audit row; in `OutboundMessage` bound the report instant to `[createdAt, now]` using the `now` argument, add both out of window cases to the report matrix unit test; in `DefaultFollowUpTouchRecorder` keep the later of the stored and new `last_outbound_message_date` and unit test it; add a sink integration test with the `Clock` bean fixed at a mid day instant in the tenant zone, one future and one pre creation `occurredAt`, asserting `sent_at` and `last_outbound_message_date` land on the bounded instant and day; update `docs/wiki/Messaging-and-Integrations.md` to say the contact link is a snapshot and that readers derive `contactRemoved`, satisfies **AC-13**, **AC-14**

## Consequences

**Positive**:
- Features 2, 3, 8 and 10 code against fixed ports, services and error codes; none of them touches the schema.
- Exactly once sending has its foundations in place before any provider call exists: immutable messages, the open message index, the attempt row shape and `send_key`.
- The first architecture test in the repository makes the dependency direction from ADR 0023 §1 a build failure instead of a review comment.

**Negative / tradeoffs**:
- The open forever problem in the premise note is real and this slice ships it as written; until the ADR is amended, a customer whose message stays `SENT` or `DELIVERED` cannot be messaged again.
- Ignored reports bump the version (so operators holding an old ETag are refused), which is a deliberate reading of ADR 0023 §4.1 "every committed transition" to include a committed event row. The benefit is that `sequence_number` and `version` stay equal and no counter query is needed.
- Snapshotting `recipient_phone` duplicates a phone number into a second table, so phone number corrections do not propagate to pending messages; operators must cancel and resubmit. With no foreign key to the contact (amended 2026-10-09), `contact_id` can outlive its contact row, a stranded pending or approved message keeps the open message slot until it is cancelled, and feature 2 owes the `contactRemoved` flag that makes this visible.
- The audit function is replaced again (the fourth signature), and every existing caller test must still pass against the new one.
- `attempt_count` is a stored derived value; a test asserts it against the attempt rows, but drift is possible if a future path inserts attempts without the aggregate method.
- Nine tasks is a large slice; task 3 alone touches four modules.

**Neutral**:
- The stub template gate is temporary scaffolding with a property that disappears in feature 6; the dev stack seed must set it for local use until then.
- The `MessagingErrorCode` enum carries codes this slice never raises, so feature 2 and 3 add no new values.
- ArchUnit becomes a test dependency to keep current.

## Follow-up

- [ ] Amend ADR 0023 §2 and §4.7 before feature 3 ships: a `SENT` or `DELIVERED` message with no further report blocks the customer forever. Proposed fix: an age bound on the index predicate, or an `INTEGRATION_MANAGE` close command for those two states, so the exactly once argument the ADR builds on the index survives. Dropping the states from the open set alone does not.
- [ ] Webhook race for features 3 and 8: Phase 3 sends inside one request, so a delivery report can reach the webhook before T8 has committed the provider id, come back `NotFound` and be dropped with `200`, leaving the message at `SENT` for good. Feature 8 should park unmatched reports keyed by provider message id and replay them after T8, or feature 3 should re query the provider after commit.
- [ ] Decide in feature 3's spec whether `CONTACT_FREQUENCY_EXCEEDED` uses `sent_at` or the follow up anchor date as its comparison point; the `(tenant_id, customer_id, sent_at)` index supports either.
- [ ] Feature 8's spec must name the deduplication key the sink relies on (`(provider, providerEventId)` in `provider_webhook_events`) since the sink itself has no event id.
- [ ] No confirmed ArchUnit Agent Skill exists in the registry; the one lead is a third party aggregator entry (`kenyasaitoh/ai_driven_dev_202601`, in Japanese) that was not opened. Write the ArchUnit test from the library documentation. Revisit if a maintained skill appears.
- [ ] Add the `dokene.messaging.template-gate.stub.enabled-intents` setting to the QA seed and `compose` environment so the local stack can submit messages until feature 6 lands.
- [ ] Soft retire phone contacts (a retired marker instead of a delete) as a customer module feature of its own, so message history can always resolve the contact it was sent to. Not needed for Phase 3; enroll it when the customer module is next touched.
- [ ] Feature 2's spec must carry the derived `contactRemoved` flag on message list and detail (no live `customer_phone_contacts` row for the message's `(tenant_id, customer_id, contact_id)`), and map `CONTACT_NOT_OWNED` in the error body.
- [ ] Feature 3's spec must run the T6 contact ownership and consent guards in the same transaction and under the same customer lock as the T7 attempt insert, refusing a removed contact with `CONTACT_NOT_OWNED`.
- [ ] `.agents/skills/README.md` should mention that ArchUnit rules live in `ModuleDependencyArchitectureTest` and that a new module adds its rule there (`/sync` owns the edit).
