# ADR 0013: Due Follow-up Queue and Operator Dispositions

## Status

Accepted

## Decision

We introduce an operator-facing, query-derived due follow-up queue (`GET /api/follow-up-queue`) and explicit
operator dispositions (snooze, dismissal, and manual follow-up completion) without introducing background
schedulers, message sending, provider integrations, or AI drafting.

### Queue Derivation and Pagination

The due queue is not stored in a separate job or task table. It is dynamically derived via PostgreSQL CTEs
reflecting current customer status (`ACTIVE`), absence of customer-wide do-not-contact (`customer_do_not_contact`),
granted WhatsApp consent (`customer_contact_consents`), effective cadence, latest valid purchase history, and
policy state (`customer_follow_up_policies`).

1. **Queue Inclusion**: Only customers whose evaluated status is `DUE` or `OVERDUE` appear in the queue.
   Customers that are `NOT_YET_DUE` (cadence not reached or active snooze) or `INELIGIBLE` (no eligible contact,
   revoked consent, archived, or do-not-contact enabled) are excluded.
2. **Ordering and Cursor Pagination**: Items are ordered deterministically by `due_date ASC, customer_id ASC`.
   Pagination uses an opaque, URL-safe Base64 cursor encoding `dueDate|customerId`.
3. **Filtering**: The queue supports optional filtering by `status=DUE` or `status=OVERDUE`.
4. **Consented Contact Selection**: The contact phone returned in queue items is resolved strictly from phone contacts
   with `GRANTED` WhatsApp consent (preferring primary when consented, else the first consented secondary phone).
   Non-consented or opted-out numbers are never returned in the queue, preventing communication to ungranted contacts.

### Operator Dispositions and State Transitions

Operators can act on due/overdue customers through three explicit dispositions:

1. **Snooze (`PUT /api/customers/{id}/follow-up-snooze`)**:
   Postpones follow-up to a specific calendar date in the tenant's time zone (`until >= today`).
   Applies only to customers currently `DUE` or `OVERDUE`; calling snooze on a customer `NOT_YET_DUE` or
   `INELIGIBLE` fails with `409 Conflict`. Input validation (e.g., date in the past) returns `400 Bad Request`.
2. **Dismissal (`POST /api/customers/{id}/follow-up-dismissals`)**:
   Dismisses the current follow-up without contacting the customer (e.g., customer recently contacted via
   another channel or follow-up not relevant this cycle).
   - Only allowed when customer status is `DUE` or `OVERDUE`. Calling dismiss on a customer `NOT_YET_DUE` or
     `INELIGIBLE` fails with `409 Conflict`.
   - Clears any active snooze date and explicit next date.
   - Sets `last_dismissed_date = today` on the customer policy, advancing the next due date to `today + effective_cadence`.
   - Accepts optional operator notes (max 500 characters).
3. **Manual Follow-up Completion (`POST /api/customers/{id}/manual-follow-ups`)**:
   Records that manual follow-up was completed outside automated channels.
   - Clears any active snooze and explicit next date.
   - Sets `last_manual_follow_up_date = today`, advancing the cadence cycle.
   - Allowed for eligible customers (including snoozed/not yet due customers if an early manual touch occurs);
     fails with `409 Conflict` if the customer is `INELIGIBLE`.
   - Accepts optional operator notes (max 500 characters).

### Anchor Precedence

Cadence calculation evaluates the latest anchor date among:
1. `last_manual_follow_up_date`
2. `last_dismissed_date`
3. latest valid purchase date (`last_purchase_date`)

The latest of these dates serves as `anchorDate`, and the next due date becomes `anchorDate + effective_cadence`.
If `snoozed_until >= today` exists, it takes precedence as `dueDate` with status `NOT_YET_DUE`.

### Concurrency and Idempotency

- **Optimistic Concurrency**: All policy updates, snoozes, dismissals, and manual completions require a strong
  numeric `If-Match` ETag matching the expected version. Stale versions reject with `409 Conflict`.
- **Atomic Eligibility Enforcement**: Dispositions enforce eligibility atomically under `READ_COMMITTED` via a
  two-layer guarantee:
  1. Governing customer row locking (`SELECT ... FOR UPDATE` on `dokene.customers`) during disposition handling,
     which serializes with concurrent consent revocations (`changeConsent`), do-not-contact updates (`changeDoNotContact`),
     and customer archival (`archiveCustomer`).
  2. Database-level eligibility predicates directly embedded in the `customer_follow_up_policies` `UPDATE` statements
     (`status = 'ACTIVE'`, no active DNC, granted WhatsApp consent), ensuring that any concurrent race that invalidates
     eligibility results in 0 rows updated and fails with `409 Conflict`.
- **Queue Snapshot Consistency**: The due queue is evaluated in a single database statement where `tenant_today`,
  cadence, and timing sources are derived from a unified `dokene.tenant_follow_up_policies` snapshot, preventing split-read
  anomalies between time zone resolution and candidate derivation.
- **Single-Instant Timestamp Alignment**: In manual follow-ups and dismissals, both the timestamp (`occurredAt`)
  and the tenant-local date (`completedOn`/`dismissedOn`) are derived from the single instant evaluated during
  eligibility check (`evaluation.evaluatedAt()`), preventing midnight-boundary inconsistencies between date and timestamp.
- **Idempotency**: Dismissals and manual completions require an `Idempotency-Key` header matching `^[A-Za-z0-9._:-]{1,128}$`.
  - The first invocation inserts the disposition record and updates policy in a single database transaction (`201 Created`).
  - An exact replay with the same key returns the existing record (`200 OK`) without re-evaluating, re-mutating policy,
    or creating duplicate audit events.
  - Reusing an idempotency key with a different customer ID rejects with `409 Conflict`.

### Security, Tenant Isolation, and Privacy Invariants

- Access requires trusted `TenantContext`, `FOLLOWUP_READ` for queue access, and `FOLLOWUP_WRITE` for dispositions.
- Strict multi-tenant isolation is enforced in every SQL query using tenant ID predicates and forced PostgreSQL Row
  Level Security (RLS) with `dokene.current_verified_tenant_id()`.
- Dispositions emit typed, durable audit events (`FOLLOW_UP_DISMISSED`, `FOLLOW_UP_SNOOZED`, `MANUAL_FOLLOW_UP_RECORDED`).
- **Privacy**: Free-form operator notes are stored in `follow_up_dismissals` and `manual_follow_up_completions` for
  operational context, but are strictly excluded from audit event payloads to prevent unstructured data leakage into audit logs.

## Consequences

- Operators can review bounded, sorted due/overdue follow-up lists and record definitive dispositions.
- Dismissals advance customer cadence cycles so dismissed customers remain eligible and return when their next cadence is reached.
- The design remains entirely provider-neutral and asynchronous-message-free.
