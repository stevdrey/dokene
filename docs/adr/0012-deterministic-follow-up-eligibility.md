# ADR 0012: Deterministic Follow-up Eligibility

## Status

Accepted

## Decision

Follow-up eligibility is a provider-neutral, read-only decision over current customer, WhatsApp consent,
purchase history, tenant policy, customer policy, manual-follow-up state, and an injected clock. Tenant policy
defines a cadence in calendar days and an IANA time zone. A customer cadence overrides the tenant cadence.

The next date uses this precedence:

1. an active snooze date;
2. an explicit customer next-follow-up date;
3. the last recorded manual follow-up date plus the effective cadence;
4. the latest valid purchase's tenant-local date plus the effective cadence.

An expired snooze no longer participates. Recording a manual follow-up clears snooze and explicit-date state, so
the next evaluation starts a new cadence. A customer without a timing anchor is ineligible with
`NO_PURCHASE_HISTORY`; an explicit date or manual follow-up permits intentional follow-up without a purchase.

The tenant-local calendar date determines `NOT_YET_DUE`, `DUE`, or `OVERDUE`. Instants are converted through
the configured IANA zone before calendar arithmetic, so daylight-saving transitions are not fixed 24-hour
intervals. Results include closed reason and timing-source enums plus the relevant date and purchase context.

Archived customers, customer-wide do-not-contact, and absence of an active WhatsApp contact with granted consent
are hard constraints evaluated before timing. Dates and cadence cannot override them.

## Persistence and isolation

Tenant and customer policies are tenant-scoped projections. Access uses trusted `TenantContext`, tenant-filtered
SQL, explicit authorization and forced PostgreSQL RLS. No caller-supplied tenant ID or ambient job context exists.

The `recordManualFollowUp` and `snooze` operations are the state-transition contract for the future manual queue.
This decision does not add a queue, scheduler, AI recommendation, draft, or outbound action.

## Consequences

- Identical inputs and clock produce identical results.
- Purchase corrections, voids, and consent changes affect the next evaluation because current state is read each time.
- Changing the tenant time zone can change local due classification without changing stored instants.
- A future queue can consume the typed result without depending on an AI or messaging provider.
