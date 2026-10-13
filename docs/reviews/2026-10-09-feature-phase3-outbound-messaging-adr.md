# Review, feature/phase3-outbound-messaging-adr, 2026-10-09

**Reviewed by**: claude-opus-5-5 (author on a different model)
**Scope**: 98 files (excluding `.pnpm-store/` and `CLAUDE.md`), branch vs main (merge base 675da09), tracked and untracked
**Verdict**: Changes requested

## Summary
The branch implements spec 0001: the `messaging` module (immutable `OutboundMessage` aggregate covering T1 to T16, submit/approve/reject/cancel services, the delivery sink), migrations V16 (six forced-RLS tables) and V17 (audit extension with a provider-attributed shape), the follow-up cadence anchor, `ProviderContext`, and an ArchUnit test. The code is careful and close to the spec: lock order, idempotency, version-equals-sequence, audit-in-transaction and the provider audit capability are all implemented correctly. There are two problems to fix before merge. First, the `ON DELETE RESTRICT` contact foreign key means a phone number that has ever received a message can never be removed or corrected, and the customer edit fails with a misleading version-conflict error. Second, the execution-time consent, do-not-contact, archived and eligibility guards on submit and approve have no tests, even though they are the security-relevant part of AC-4.

## Major

### 🟠 Contact FK with RESTRICT permanently blocks removing or correcting a messaged phone, `backend/src/main/resources/db/migration/V16__create_outbound_messaging.sql:42`
**Problem**: `fk_outbound_messages_contact` references `customer_phone_contacts(tenant_id, customer_id, id) ON DELETE RESTRICT`. `JdbcCustomerRepository.update` (lines 141-145) handles a removed or edited phone by deleting the old `customer_phone_contacts` row. Once any message has been submitted to that contact, including messages that are long terminal (`READ`, `CANCELLED`, `REJECTED`, `FAILED`), that delete raises a foreign key violation. The repository turns the violation into `CustomerConflictException`, so the whole customer update rolls back.
**Why it matters**: After Phase 3 ships, an operator can never fix a wrong number or remove a stale number for any customer who has been messaged. The error looks like a concurrency conflict, so operators will retry and keep failing. This contradicts the spec's own trade-off ("phone number corrections do not propagate to pending messages; operators must cancel and resubmit"), which assumes corrections still work. Consent rows use `ON DELETE CASCADE` on the same parent, so this is also inconsistent with how the schema handles contact removal elsewhere. No test covers editing a customer's phones after a message exists.
**Suggested fix**: Decide this explicitly in the spec, since this is the costly-to-redo data model. Options: (a) soft-retire contacts (an `active` flag) instead of deleting them, so the FK stays valid; (b) keep `recipient_phone` as the immutable snapshot and constrain only `(tenant_id, customer_id)`, then validate contact ownership in the service at submit time; (c) keep RESTRICT, but have the customer service map this specific violation to a clear domain error and document that a messaged contact is permanent. Add an integration test for whichever option you choose.

### 🟠 Execution-time T1/T2 policy guards (consent, do-not-contact, archived, eligibility, approve re-check) are untested, `backend/src/main/java/io/github/stevdrey/dokene/messaging/application/OutboundMessageCommandService.java:242`
**Problem**: `runSharedGuards` holds the security-relevant branches of AC-4: `CUSTOMER_ARCHIVED`, `DO_NOT_CONTACT`, `NO_CONTACT_CONSENT` (per contact and channel), `FOLLOW_UP_INELIGIBLE`, plus re-running all of them at approve time (T2). No test anywhere in `messaging` or `OutboundMessagingIntegrationTest` raises any of those four codes, and no test changes consent, do-not-contact or archive status between submit and approve. The only guard refusals tested are `STALE_VERSION`, `TEMPLATE_NOT_MAPPED`, `BODY_REJECTED` and `NOT_FOUND` on submit.
**Why it matters**: These guards keep the system from contacting a customer who revoked consent or asked not to be contacted, which is security invariant 24 and ADR 0023 §2. A future refactor that drops or reorders one of them (for example, checking consent on the wrong `contactId`, or skipping `runSharedGuards` on approve) would pass the current suite.
**Suggested fix**: Add integration tests that (1) refuse submit for an archived customer, a do-not-contact customer, a contact without `GRANTED` WhatsApp consent (including the case where another phone of the same customer has consent), and a `NOT_YET_DUE` customer, and (2) submit, then revoke consent or set do-not-contact, then assert that approve is refused with the right code and writes no event or audit row.

## Minor

### 🟡 AC-2 index backstop and AC-5 same-key concurrency are not exercised, `backend/src/test/java/io/github/stevdrey/dokene/tenant/security/TenantIsolationSecurityIntegrationTest.java:253`
**Problem**: The index is only checked by matching the text of `indexdef`. No test inserts a second open message while bypassing the service, and the `23505` to `MESSAGE_ALREADY_OPEN` translation in `JdbcOutboundMessageRepository.insert` (line 62) is never reached, because the customer `FOR UPDATE` lock serializes the concurrent-submit test before the index fires. The spec's "two threads replay the same key, one row results" scenario (AC-5) is also missing.
**Suggested fix**: Insert directly through the repository (or runtime SQL) to assert both the constraint and the translated code. Add a two-thread test that submits with the same idempotency key and asserts one message, one key row, and one result with `created=false`.

### 🟡 AC-8 cross-tenant coverage is partial, `backend/src/test/java/io/github/stevdrey/dokene/tenant/security/TenantIsolationSecurityIntegrationTest.java:268`
**Problem**: The raw-SQL check covers one cross-tenant INSERT into the idempotency table and one DELETE. It never seeds tenant A rows in the six tables and then SELECTs under tenant B's context. It also never tries a cross-tenant replay (tenant B reusing tenant A's key and fingerprint). The service-level test covers only `cancel` from tenant B.
**Suggested fix**: After the happy path, run `SELECT count(*)` on each of the six tables under tenant B's signed context and assert zero. Add a replay attempt from tenant B.

### 🟡 Guard path can throw exceptions other than `MessagingRefusedException`, `backend/src/main/java/io/github/stevdrey/dokene/messaging/application/OutboundMessageCommandService.java:256`
**Problem**: The spec says `MessagingRefusedException` is "the only refusal type the module throws". But `followUps.evaluateSnapshot` requires `FOLLOWUP_EVALUATE` and throws a raw `TenantAccessDeniedException` (audited under a follow-up permission). `grounding.assemble` (line 264) can throw `RecommendationContextException(UNSUPPORTED)` on its contact-eligibility or purchase-race branches. Every current role that has `MESSAGE_DRAFT` also has `FOLLOWUP_EVALUATE`, so this is latent, but feature 2's problem+json mapping will turn these into 500s.
**Suggested fix**: Catch these two exceptions inside `runSharedGuards` and map them (`FORBIDDEN` and `FOLLOW_UP_INELIGIBLE`), or document the coupling between permissions.

### 🟡 Provider-supplied `occurredAt` drives `sent_at` and the cadence anchor with no bounds, `backend/src/main/java/io/github/stevdrey/dokene/messaging/domain/OutboundMessage.java:172`
**Problem**: On T13, `sentAt` comes from the report's `occurredAt`, and the sink passes it to `FollowUpTouchRecorder` (`DefaultDeliveryStatusSink.java:85`). A skewed or far-future timestamp would set `last_outbound_message_date` in the future and keep the customer out of the due queue.
**Suggested fix**: Clamp `occurredAt` to `now` in the domain or the sink, or record a precondition that feature 8 must enforce at the webhook boundary.

### 🟡 Provider audit path does not require a bound `ProviderContext`, and it depends on an undocumented `AuditExecutionContext`, `backend/src/main/java/io/github/stevdrey/dokene/audit/persistence/jdbc/TransactionalAuditRecorder.java:157`
**Problem**: `messageDeliveryUpdated` checks only that no member context is bound, so any caller that holds a `TenantId` can mint a provider-attributed audit row (the database still restricts it to `MESSAGE_DELIVERY_UPDATED` for the verified tenant). The sink also fails at audit time unless a correlation context is bound. The integration test binds one with `auditExecution.callWithCorrelation`, but neither the spec nor the `ProviderContext` docs tell feature 8 to bind it.
**Suggested fix**: Have the recorder check `ProviderContextProvider.current()` for a matching tenant. Document in `ProviderContext` or the spec that the webhook boundary must bind both contexts.

### 🟡 Security invariant 24 overstates what the code does, `docs/security/security-invariants.md:29`
**Problem**: The invariant says "Every operator command ... re-checks customer status, do-not-contact, consent and deterministic follow-up eligibility". Reject and cancel (T3 to T5) only check `If-Match`, and the ADR intends that. It also says every state change appends an event "inside the same transaction as its audit record", but ignored reports and T7 write no audit row.
**Suggested fix**: Limit the re-check clause to submit, approve and send, and add an exception for non-applied reports and T7.

### 🟡 ArchUnit rule has no negative proof, `backend/src/test/java/io/github/stevdrey/dokene/architecture/ModuleDependencyArchitectureTest.java:24`
**Problem**: The spec scenario "an intentional followup to messaging import in a test fixture fails the ArchUnit rule" is not implemented. If the package patterns are wrong, the rules pass without checking anything.
**Suggested fix**: Add a test that imports a small fixture class set containing a violating dependency and asserts that `rule.evaluate(...).hasViolation()` is true.

## Nits
- ⚪ `backend/src/main/java/io/github/stevdrey/dokene/messaging/application/CommandResult.java:9`: `Optional<Object> record` forces feature 2 to use `instanceof`; a small sealed `MessageRecord` type (approval or cancellation) would make the contract explicit.
- ⚪ `backend/src/main/java/io/github/stevdrey/dokene/followup/application/DraftGroundingAssembler.java:36`: fully qualified `java.util.Optional` used three times; import it.
- ⚪ `backend/src/main/java/io/github/stevdrey/dokene/messaging/application/OutboundMessageCommandService.java:62`: the spec says the validator draft uses an empty rationale, but the code uses a fixed Spanish rationale. The code comment explains why; record the deviation in the spec.
- ⚪ `backend/src/main/java/io/github/stevdrey/dokene/messaging/application/OutboundMessageCommandService.java:214`: the "other open message, excluding this one" check on approve can never match while the index exists. It is harmless, but an assertion would state the intent more honestly.
- ⚪ `backend/src/main/java/io/github/stevdrey/dokene/messaging/domain/MessageEvent.java:9`: `sequenceNumber` is `long` while the column is `INTEGER` and `version` is `BIGINT`; align the types.
- ⚪ `compose.override.yaml:1`: a local port override (5433) is untracked in the repo root. Gitignore it or leave it out of the PR.

## Strengths
- The aggregate is pure and fully table-tested. The exhaustive command matrix (ten states × both `outcomeUnknown` values × every command) and the report matrix map directly to AC-3. Every exit from `SENDING` clears `outcomeUnknown` by construction through `copy(...)`.
- The provider audit shape is designed with care. It has a separate `provider` capability prefix, nil-UUID actor slots that the database rejects if non-nil, the restriction to `MESSAGE_DELIVERY_UPDATED`, and the tenant match against `current_verified_tenant_id()`. All existing positional callers keep working through the defaulted trailing parameters.
- Lock order (advisory, then customer, then message) is the same in the command service and the sink. Version equals sequence number, including on ignored reports. Audit, event and idempotency rows commit atomically, and a test proves that an audit failure rolls back all three.
- Logs carry only ids, status pairs and codes, and the happy-path test asserts that the body, note, phone and provider id never appear in any log line.

## Test coverage
Covered: the transition and report matrices (domain), fingerprint canonicalization, input validation before any lock, the stub template gate, sink unit behaviour (no context, member context, not found, provider-id mismatch, applied, ignored, cadence touch on T13), and an end-to-end happy path against Postgres (submit, replay, key reuse, already open, approve, the driver T6 to T8, delivered, late, not found, events 1 to 7, audit attribution, cadence anchor, clean logs). Also covered: reject and cancel with replay, stale version, foreign tenant, viewer denial, audit rollback, a four-writer concurrent submit, RLS, policies and grants on all six tables, and the evaluator with the new anchor.

Not covered: the T1/T2 consent, do-not-contact, archived and eligibility guards, and the approve-time re-check (Major); the index backstop and its `23505` translation; concurrent same-key first requests; cross-tenant SELECTs and replay against the messaging tables; customer phone edits after a message exists; and a negative proof for the ArchUnit rules.
