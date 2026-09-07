# ADR 0009: Tenant Customer Profiles and Phone Identity

## Status

Accepted

## Decision

Dokene stores customer profiles as tenant-scoped aggregates. A profile has a required display name, optional
bounded notes, one to ten phone contacts, exactly one primary phone, an optimistic version, and an
`ACTIVE` or `ARCHIVED` status. Consent, purchases, provider identifiers, contact merging, and outbound actions
remain separate concerns.

Phone input always includes an ISO 3166-1 alpha-2 region and is validated with libphonenumber before being
stored only as E.164. `(tenant_id, normalized_phone)` is unique across all customers, including archived
customers. The same normalized phone may independently exist in different tenants. Concurrent conflicts fail
with `409 Conflict`; creation never reactivates an archived profile.

Updates replace the complete profile and phone collection and require the current optimistic version. Archive
is an idempotent logical transition, requires `If-Match`, preserves contacts and future references, and has no
restore endpoint. Active lists exclude archived profiles by default; authorized callers may explicitly list
archived/all profiles or retrieve an archived profile by ID.

All customer access requires server-derived `TenantContext`, explicit customer permission checks, application
ownership checks, tenant-filtered SQL, and forced PostgreSQL RLS. Create, update, and archive append a successful
audit event atomically with only the customer UUID; names, notes, and phone numbers are never audit metadata.

## API and Pagination

The tenant-scoped `/api/customers` API supports create, get, full update, archive, and bounded search. Search is
ordered by `(created_at DESC, id DESC)` and uses an opaque exclusive cursor. Page size defaults to 50 and is
capped at 100. Name matching is case-insensitive bounded substring matching; phone matching is exact after the
same region-aware normalization used for writes. Notes are not searchable.

## Consequences

- Historical customer identity remains stable and physical deletion is unavailable to the runtime role.
- Reusing a phone held by an archived record requires a future explicit merge or administrative policy.
- Phone-plan metadata is a versioned dependency and should be updated deliberately.
- PostgreSQL integration and concurrency tests are required for changes to these rules.
