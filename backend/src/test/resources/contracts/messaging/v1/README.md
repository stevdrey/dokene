# Messaging contract fixtures — v1

Frozen, synthetic examples of the Phase 3 messaging HTTP contract ([ADR 0023](../../../../../../../docs/adr/0023-phase-3-outbound-messaging-contracts.md) §3.1, [ADR 0024](../../../../../../../docs/adr/0024-phase-3-messaging-contract-freeze.md)).

Two consumers read these files, and both fail on any incompatible change:

- backend: `MessagingContractFixturesTest` (and `IntegrationContractFixturesTest` / `TemplateContractFixturesTest` for `integration.json` / `templates.json`) deserializes every valid example into `messaging.api.MessagingApiContract` records, re-serializes it to the same JSON tree, rejects every `invalid` example and compares `enums.json` with the Java enums;
- frontend: `src/features/messages/__tests__/contractFixtures.test.ts` checks every example against the TypeScript contract in `src/features/messages/types`.

| File | Content |
| --- | --- |
| `enums.json` | Every closed value set the HTTP contract exposes. |
| `messages.json` | Message detail responses for each lifecycle state (pending approval, approval, rejection, cancellation, queueing, provider acceptance, delivery, read, permanent and transient failure, unknown outcome, webhook failure, manual resolution), list pages and event histories. |
| `requests.json` | Valid and invalid command bodies for submit, approval, cancellation and resolution. |
| `integration.json` | The `/api/integrations/whatsapp` request (valid and invalid bodies) and response (ADR 0023 §3.3, issue 134), read by `IntegrationContractFixturesTest` and the same frontend test. |
| `templates.json` | The `/api/templates` request (valid and invalid bodies), per-intent response and list (ADR 0023 §3.2, ADR 0025, issue 135), read by `TemplateContractFixturesTest` and the same frontend test. |
| `problems.json` | One `application/problem+json` body per `MessagingErrorCode`, plus invalid problem bodies. |

Rules:

- Data is synthetic only: fixed `7a5c0000-…` UUIDs, `+506000000xx` numbers and `wamid.SYNTHETIC-…` provider ids. Never copy real customer, phone, provider or secret values here.
- Renaming, removing or retyping a member, or removing an enum value, is a breaking change: add a `v2` directory and amend ADR 0024; do not edit `v1` in place.
- Adding an optional member or enum value is allowed only in the same PR that updates the Java records, the TypeScript contract and these fixtures, reviewed by the contract owner (Issue #132 lineage).
