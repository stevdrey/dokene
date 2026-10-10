# Verify: message data model and state machine · spec 0001 · updated 2026-10-09 · verified 2026-10-09 (33 of 34 steps ran and passed; the sink tenant probe was not exercised, see /check verify report)
_Steps derived from spec 0001 acceptance criteria. `/check verify` runs these; `/test` locks the durable ones._

## UI / manual
This slice has no HTTP or UI surface. Every step below runs as a command against the Testcontainers Postgres the backend tests start, or as a direct SQL check on a migrated database.

## Commands
- [x] `cd backend && ./gradlew test --tests 'io.github.stevdrey.dokene.tenant.security.TenantIsolationSecurityIntegrationTest' --tests 'io.github.stevdrey.dokene.tenant.persistence.jpa.TenantPersistenceIntegrationTest'` → V16 and V17 apply; six messaging tables have `tenant_id`, forced RLS, per command policies, migration policy, DML only grants; `outbound_message_events` grants `SELECT, INSERT` only → AC-1, AC-8
- [x] On a migrated database: `SELECT conname FROM pg_constraint WHERE conrelid = 'dokene.outbound_messages'::regclass AND contype = 'f'` → exactly `fk_outbound_messages_tenant` and `fk_outbound_messages_customer`, no contact foreign key → AC-13
- [x] `./gradlew test --tests 'io.github.stevdrey.dokene.messaging.domain.OutboundMessageTransitionMatrixTest'` → every (state, outcomeUnknown, command) cell matches ADR 0023 §2 T1 to T12, every other cell refuses `INVALID_TRANSITION` → AC-3
- [x] `./gradlew test --tests 'io.github.stevdrey.dokene.messaging.domain.OutboundMessageDeliveryReportMatrixTest'` → ten states by four report statuses match the report table, non applied cells yield `Ignored` with a version bump; a future `occurredAt` lands on the applying instant, one before `createdAt` lands on `createdAt`, one inside the window is kept → AC-3, AC-14
- [x] `./gradlew test --tests 'io.github.stevdrey.dokene.tenant.security.OutboundMessagingIntegrationTest'` → submit, replay (`created=false`, no mutation), key reuse `IDEMPOTENCY_KEY_REUSED`, second submit `MESSAGE_ALREADY_OPEN`, approve, T6 to T8, delivered, late report ignored, unknown id `NotFound`, events 1 to 7 with `sequence_number == version`, provider attributed audit, cadence anchor `LAST_OUTBOUND_MESSAGE`, clean logs → AC-2, AC-4, AC-5, AC-6, AC-7, AC-9, AC-10
- [x] Same suite, `phoneCorrectionAfterSubmitSucceedsAndStrandsThePendingMessage` → customer edit replacing the messaged phone succeeds and deletes the old contact row; message keeps `contact_id` and `recipient_phone`; approve refused `CONTACT_NOT_OWNED` with one event row and no `MESSAGE_APPROVED` audit or approval row → AC-13
- [x] Same suite, `deliveryReportTimesAreBoundedAndTheCadenceAnchorNeverMovesBackwards` → a report dated seven days ahead sets `sent_at` to the applying instant and the anchor to that day; a report dated before creation sets `delivered_at` to `created_at`; a touch with an older instant leaves `last_outbound_message_date` unchanged → AC-14
- [x] Same suite, `refusesSubmitWhenForbiddenStaleUnmappedOrCrossTenantWithoutLeavingRows` and `auditFailureRollsBackTheMessageItsEventAndItsIdempotencyKey` → `FORBIDDEN` audited as `AUTHORIZATION_DENIED`, `STALE_VERSION`, `TEMPLATE_NOT_MAPPED`, `BODY_REJECTED`, cross tenant `NOT_FOUND`, zero rows left; audit failure rolls back message, event and key → AC-4, AC-6, AC-12
- [x] `./gradlew test --tests 'io.github.stevdrey.dokene.tenant.security.OutboundMessagingGuardIntegrationTest'` → archived, do not contact, missing consent and not due customers are refused on submit with their codes and on approve after the change → AC-4
- [x] `./gradlew test --tests 'io.github.stevdrey.dokene.messaging.application.DefaultDeliveryStatusSinkTest' --tests 'io.github.stevdrey.dokene.tenant.application.ScopedValueProviderContextProviderTest'` → sink refuses without a `ProviderContext`, refuses with a member context, `NotFound` for unknown id and id mismatch → AC-7
- [x] `./gradlew test --tests 'io.github.stevdrey.dokene.followup.*'` → evaluator treats the latest of four anchors as the cadence anchor with `LAST_OUTBOUND_MESSAGE`; touch recorder converts the instant with the tenant zone → AC-9
- [x] `./gradlew test --tests 'io.github.stevdrey.dokene.architecture.ModuleDependencyArchitectureTest'` → build fails on a `followup`, `customer`, `purchase`, `ai`, `tenant` or audit dependency on `messaging`, or `messaging.domain` on Spring or JDBC → AC-11
- [x] `./gradlew test --tests 'io.github.stevdrey.dokene.messaging.domain.RequestFingerprintTest'` → null and empty fields hash differently, field order fixed per operation → AC-5

## Value sourcing checks
One step per Value sourcing row, each varying the input that breaks if the source were wrong.
- [x] submit `recipient_phone`: submit with a `contactId` of another customer in the same tenant → `NOT_FOUND`; with the right contact the stored `recipient_phone` equals that contact's E.164 → AC-4
- [x] submit consent: grant consent on phone A, submit to phone B of the same customer → `NO_CONTACT_CONSENT` → AC-4
- [x] submit follow up status: customer `NOT_YET_DUE` → `FOLLOW_UP_INELIGIBLE` → AC-4
- [x] submit `source_policy_version`: `If-Match` of `policyVersion + 5` → `STALE_VERSION`; the stored `source_policy_version` equals the evaluation's version → AC-4
- [x] body safety: body with a URL → `BODY_REJECTED` on submit → AC-4
- [x] template mapping: intent not in `dokene.messaging.template-gate.stub.enabled-intents` → `TEMPLATE_NOT_MAPPED`; listed → accepted → AC-12
- [x] `send_key`: two different messages never share a `send_key`; replay returns the same key → AC-5
- [x] open message check: insert a second open row by SQL under the tenant context while one is open → unique violation on `one_open_message_per_customer` → AC-2
- [x] timestamps from the clock: event `occurred_at` of an applied report equals the applying instant, not the report's `occurredAt` → AC-10
- [x] `version` and `sequence_number`: after seven transitions the last event's `sequence_number` is 7 and equals `version` → AC-10
- [x] actor attribution: SUBMITTED and APPROVED events carry `MEMBER`, T7 to T11 `SYSTEM`, DELIVERY_UPDATED `PROVIDER` → AC-10
- [x] fingerprint: same key with a changed body → `IDEMPOTENCY_KEY_REUSED` → AC-5
- [x] replay representation: replay of approve returns the stored approval as the child record with `created=false` → AC-5
- [x] `maxSendAttempts`: `completeAttempt(FailedTransiently)` at the max goes to `FAILED`, below it back to `APPROVED` → AC-3
- [x] `provider_message_id`: `OutcomeUnknown(Optional.of(id))` stores the id so a later report finds the message → AC-7
- [x] report times bounded: future and pre creation `occurredAt` land on `now` and `createdAt` → AC-14
- [x] `failure_category`: `FAILED` report without a category stores `UNKNOWN` → AC-3
- [x] tenant local date: tenant zone `America/Santiago`, instant `02:30Z` → anchor date is the previous day → AC-9
- [x] evaluator anchor: a purchase today and an outbound message yesterday → anchor is the purchase, timing source not `LAST_OUTBOUND_MESSAGE` → AC-9
- [ ] sink tenant: report under tenant B's provider context for tenant A's provider id → `NotFound` → AC-8
- [x] logs: no log line contains the body, the note, the phone or the provider id → AC-10

## Acceptance-criteria coverage
- AC-1 · migrations and RLS suites · AC-2 · integration happy path and index step · AC-3 · both matrix tests · AC-4 · integration refusals and guard suite · AC-5 · fingerprint and replay steps · AC-6 · audit rollback step · AC-7 · sink tests · AC-8 · isolation suite and sink tenant step · AC-9 · followup suite and anchor steps · AC-10 · events, attribution and logs steps · AC-11 · ArchUnit step · AC-12 · template gate step · AC-13 · constraint query and phone correction test · AC-14 · report matrix and bounded report test
