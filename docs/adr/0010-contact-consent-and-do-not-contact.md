# ADR 0010: Contact Consent and Do-Not-Contact

## Status

Accepted

## Decision

Dokene models consent as deterministic, tenant-scoped customer state. The first supported channel is
`WHATSAPP`. Consent is attached to a stable phone-contact UUID rather than to the customer profile or raw phone
value. A contact without a grant or revocation is `UNKNOWN`; only `GRANTED` is eligible. Replacing a phone creates
a new contact identity with unknown consent, while immutable history retains the previous contact UUID without
copying the phone number.

Consent evidence records a closed source (`CUSTOMER_VERBAL`, `CUSTOMER_WRITTEN`, or `OPERATOR_CORRECTION`), a
server timestamp, and actor and membership IDs derived from trusted `TenantContext`. Do-not-contact is customer-wide
and overrides every channel and consent grant. Clearing it changes only the override and never creates or restores a
grant that did not already exist.

The current projections support efficient eligibility evaluation. Separate append-only histories preserve every
submitted grant, revocation, activation, and clearing event. Mutations require the current contact-policy version
through `If-Match`. Profile and contact-policy versions are logically independent, but the stable contact identity
set is part of the policy representation: adding, removing, or replacing an identity advances the policy version
atomically with the profile update. Profile-only changes and primary-flag changes over the same identities do not.
Successful mutations and privacy-safe
general audit events commit atomically.

All reads and writes require customer permissions, application ownership checks, tenant-filtered SQL, and forced
PostgreSQL RLS. Runtime history access is limited to `SELECT` and `INSERT`; history contains no names, phone numbers,
notes, free text, provider payloads, or client-supplied timestamps and actors.

## API and History

The customer API exposes the current policy, bounded cursor history, explicit consent and do-not-contact mutations,
and deterministic eligibility. History is ordered by `(occurred_at DESC, id DESC)`, defaults to 50 entries, and is
capped at 100. Eligibility returns all applicable closed reasons, with do-not-contact listed first.

## Consequences

- Replacing contact identity cannot silently transfer consent.
- Removing a contact removes its current projection but preserves its evidence history.
- Adding channels requires an explicit schema/domain/API change and does not inherit WhatsApp consent.
- This decision records customer intent; it does not claim legal compliance or authorize outbound sending.
