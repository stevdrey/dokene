# ADR 0011: Purchase History and Last-Purchase Tracking

## Status

Accepted

## Decision

Dokene stores purchases as tenant-scoped customer activity signals, not accounting records. A purchase contains a
UTC instant and a required, trimmed description of at most 500 UTF-16 code units. The API accepts ISO-8601 instants,
stores PostgreSQL `TIMESTAMPTZ` values at microsecond precision, and rejects future purchase instants according to
server time. There is intentionally no product catalog, quantity, price, tax, invoice, payment, or inventory model.

Each create requires an `Idempotency-Key` of 1-128 characters from the closed ASCII set
`A-Z a-z 0-9 . _ : -`. Keys are unique per tenant. Repeating a key with the same customer, timestamp, and normalized
description returns the original purchase without another history or audit event; a different payload returns
`409 Conflict`. The unique constraint and conflict-aware insert make this rule safe under concurrent retries.

Corrections replace the purchase timestamp and description and require the current version through `If-Match`.
Voiding is a logical, irreversible transition and also requires `If-Match`; no runtime delete is available. Tenant,
customer, creation identity, idempotency key, and submission fingerprint are database-immutable. Every accepted
record, correction, and void appends a revision snapshot with trusted actor attribution and a separate privacy-safe
audit event containing only the purchase UUID. Purchase descriptions never enter audit metadata.

Last purchase is derived at read time as the greatest `(purchased_at, id)` among valid purchases for the customer.
It is not cached on the customer row, so backdated insertion, timestamp correction, concurrent writes, and voiding
cannot leave stale last-purchase state. A customer without valid history receives `204 No Content`.

## Authorization and isolation

`PURCHASE_READ` and `PURCHASE_WRITE` are explicit tenant permissions. Owners, administrators, and operators may
read and write; viewers may read. Application services first load and authorize the owning customer and then verify
purchase resource ownership. Composite foreign keys keep purchase/customer tenant IDs consistent, and forced RLS
protects both current purchases and revision history. Archived customers retain readable/correctable history but do
not accept new purchases.

## Pagination

Purchase lists and per-purchase revision history use opaque exclusive cursors over their descending timestamp/UUID
sort orders. The default page size is 50 and the accepted range is 1-100. Purchase-list pages are not snapshots;
concurrent inserts can appear before a previously issued cursor.

## Consequences

- Corrections and voids remain explainable without silently destroying customer history.
- Descriptions are customer data and receive the same tenant isolation as customer profiles.
- Read-time last-purchase derivation favors correctness and simplicity; a future measured scale problem may justify
  a transactionally maintained projection without changing this source-of-truth history.
