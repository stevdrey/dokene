# 0001. Message data model and state machine: decision record

The build spec is [index.md](index.md). This file holds the reasoning `/develop` does not need.

## Context

> ⚠️ Premise note: ADR 0023 §2 counts `SENT` and `DELIVERED` as open states for the one open message per customer rule, and the partial unique index in §4.7 enforces that at the database. `DELIVERED` has exactly one exit, a `read` report (T15), and `SENT` leaves only through a `delivered`, `read` or `failed` report. A customer who never opens the chat, a tenant whose provider does not emit read receipts, or a delivery report that never arrives, therefore leaves one open message forever, and every later submit for that customer is refused with `MESSAGE_ALREADY_OPEN` with no operator action that clears it. This spec implements the ADR as written so that it does not reopen an accepted decision, but the rule needs an amendment before Phase 3 reaches real customers. The amendment must also keep the exactly once argument the ADR makes from this index: an age bound on the index predicate, or an `INTEGRATION_MANAGE` close command for `SENT` and `DELIVERED` messages, preserves it; simply dropping those states from the open set does not. It is recorded in Follow-up as an ADR amendment to raise before feature 3 ships.

Roadmap Phase 3 sends the first real message to a customer. ADR 0023 decided the module ownership, the ten states, the sixteen transitions, the six messaging tables, the audit event names, the closed error codes and the permission matrix. What it leaves open is everything a builder would otherwise invent: the exact columns and constraints, how the aggregate is shaped in Java, how a transition is refused, how idempotency replay reconstructs a response, how event history stays ordered, and which of the surrounding changes (audit shape, follow up anchor, architecture test) land with this slice rather than a later one.

The forces are the repository's existing conventions and its security invariants. Persistence is JDBC with Java records and hand written row mappers, not JPA. Versions are manual `BIGINT` columns compared in the `UPDATE` predicate. Every tenant table carries forced row level security (RLS, the database refuses rows from another tenant even to buggy application code) keyed on a signed tenant context. Audit rows are appended through a `SECURITY DEFINER` function whose check constraints enumerate every permitted event shape, and business services join the audit write to their own transaction so a failed audit rolls the business change back. Idempotency keys already exist for follow up dispositions and a SHA-256 request fingerprint already exists for purchases. The data model is the costliest thing to redo, and nine later features build on these tables, so the cost of a wrong column now is paid ten times.

The constraint that shapes this slice most is that the provider, the template catalogue and the webhook do not exist yet. The state machine still has to be complete, because T8 to T16 are domain rules, not provider details. So the ports those later features implement are defined here with their result types, and the one guard that needs data from a later feature (template mapping) gets a port with a fail closed stand in.

> Amendment, 2026-10-09. The fresh model review of the built slice found that the three column foreign key from `outbound_messages` to `customer_phone_contacts` with `ON DELETE RESTRICT` blocks the customer repository, which removes a phone by deleting its contact row, for any customer who was ever messaged. The customer edit then fails with a version conflict error that operators will retry forever. The spec's own trade off (corrections do not reach pending messages, cancel and resubmit) assumed corrections still work. This amendment settles the contact lifecycle (AC-13) and bounds the provider supplied report time that drives `sent_at` and the cadence anchor (AC-14). Both are design changes, so they live here and not in the review.

## Options considered

### Option 1: Immutable record aggregate with transition methods and command services

`OutboundMessage` is a Java record. Each command is a method returning a `Transition` (the next record plus the event to append) or throwing `MessagingRefusedException` with a `MessagingErrorCode`. Services lock rows, call the method, persist the result and audit. Ports for the provider, the delivery sink and the template gate are defined now.

**Pros**:
- Matches the record and JDBC idiom of every existing module, so the row mappers, version handling and lock queries are copies of known code.
- The whole transition table is pure and testable as a matrix with no database.

**Cons**:
- Each transition allocates a new record and the service must remember to persist the returned one, not the one it loaded. A test that compares the persisted version to the returned version guards this.

### Option 2: Transition table as data with a thin aggregate

A `MessageStateMachine` class holds a map from (state, command) to target state and a guard function. The aggregate is a plain record with no behaviour.

**Pros**:
- The table reads like ADR 0023 §2 and is easy to audit for completeness.

**Cons**:
- Guards that depend on fields beyond the state (attempt count for T10, `outcomeUnknown` for T11 to T13, provider id presence) become special cases outside the table, which is exactly where the bugs hide.

### Option 3: Mutable aggregate class with apply methods

A classic domain object with private fields mutated by `approve()`, `send()` and so on.

**Pros**:
- Familiar to readers of DDD literature, no returned copies to mix up.

**Cons**:
- Breaks the record convention used by customer, purchase and followup, and mutable state plus a manual version column invites double increments.

## Rationale

The repository already has a working answer for every mechanical question this feature raises: records with JDBC for persistence, manual version columns for strong ETags, `FOR UPDATE` on the customer row for serialization, `ON CONFLICT DO NOTHING` plus reselect for idempotency, a SHA-256 hex fingerprint for request equality, an advisory lock on hashed key parts for first request serialization, and `PROPAGATION_MANDATORY` audit writes. Option 1 reuses all of them and adds only what ADR 0023 requires. Options 2 and 3 each introduce a second way of doing something the codebase already does one way, and the guards with side inputs (attempt count, unknown outcome, provider id) sit most naturally as method logic on the record that holds those fields.

Defining the provider, sink and template ports here rather than in features 3, 6 and 8 follows from the state machine being complete only when T8 to T16 take real typed inputs. A sealed `ProviderSendResult` is a domain fact (accepted, rejected for good, failed for now, unknown), not a provider detail, and writing it once stops three later specs from each inventing a shape.

Delivering the follow up anchor and the full audit extension in this slice follows from the migration cost argument in Context: both are schema changes that every later feature assumes, and `MESSAGE_DELIVERY_UPDATED` cannot be written at all until the attribution constraint allows a provider shaped row.

**Contact lifecycle (amended 2026-10-09).** Three options were weighed once the review surfaced the blocked phone edit. (a) Soft retire contacts: add a retired marker to `customer_phone_contacts` so rows are never deleted and the foreign key stays valid. This keeps the most faithful history, but it is a customer module change that touches the unique phone per tenant constraint (a retired number could not be reused by another customer without more work), the consent and eligibility reads, the customer API and the UI. It is the right long term shape and is recorded in Follow-up as its own feature, not smuggled into the messaging slice. (b) Snapshot only: drop the contact foreign key, keep `contact_id` as a historical id and `recipient_phone` as the immutable number, and prove ownership in the service at submit. This is the smallest change, stays inside `messaging`, and matches how the message already treats `recipient_phone` as a frozen copy. The one risk, a pending message to a removed contact advancing anyway, is already closed by the approve time consent re check, since consent rows cascade with the contact. (c) Keep `RESTRICT` and map the violation to a clear domain error: cheapest, but it makes any messaged phone permanent, so operators could never fix a wrong number, which contradicts the trade off this spec accepted. Option (b) is chosen. The foreign key was giving a referential guarantee the design never relied on after submit, at the price of freezing the customer module's existing behavior. Two more shapes were looked at and rejected: `ON DELETE SET NULL` collides with `contact_id NOT NULL` and with the identity trigger that forbids changing `contact_id`, so it would fail the same delete; and (d) a `BEFORE INSERT` trigger or `INSERT ... SELECT` that proves the contact row exists at insert time, which keeps the database's own ownership check without blocking later deletes. (d) was rejected because the service already proves ownership under the customer lock before every insert, the runtime role is the only writer, and a second check in SQL would be a duplicate to keep in step for a guarantee the design does not rely on after submit. It stays available if a second writer ever appears.

**Report time bound (amended 2026-10-09).** A provider supplied `occurredAt` drove `sent_at` and the cadence anchor with no bound, so a skewed or malicious webhook payload could move `last_outbound_message_date` either way: into the future, hiding a customer from the due queue indefinitely, or far into the past, setting `sent_at` before the message existed and pulling a just messaged customer back into the queue. The bound is therefore a window, not a single clamp: no earlier than the message's own `created_at` (a fact on the record) and no later than the clock instant at which the report is applied. Bounding in the domain method, not at the webhook edge, means feature 8 cannot forget it and the local test provider in feature 3 is covered too. The touch recorder adds a second guard of its own, never moving the anchor backwards, because the anchor is also written by dispositions and purchases and should be monotonic regardless of who calls it.
