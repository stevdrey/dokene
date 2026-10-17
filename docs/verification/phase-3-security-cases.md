# Phase 3 Security Cases

This is the list of security cases for Phase 3 (WhatsApp sending with manual approval). It turns the [threat model](../security/threat-model.md) into rows that a person or a test can run one by one.

It is preparation. It does not say Phase 3 is secure. Most of the HTTP surface these cases call does not exist yet, and nothing here has been run against the integrated stack. That run belongs to [#149].

This document is the output of [#141] and is documentation only. No product behavior changes and no change to `TenantRolePermissions`.

## Sources

| What | Where |
| --- | --- |
| Threats and trust boundaries | [Threat model](../security/threat-model.md) |
| Invariants (the numbers in the `Inv` column) | [Security invariants](../security/security-invariants.md) |
| States, routes, error codes, permission matrix | [ADR 0023](../adr/0023-phase-3-outbound-messaging-contracts.md) (sections 2 to 5) |
| Role to permission map | [`TenantRolePermissions`](../../backend/src/main/java/io/github/stevdrey/dokene/tenant/domain/TenantRolePermissions.java) |
| Database isolation rules | [Tenant isolation RLS recipe](../architecture/tenant-isolation-rls-recipe.md) |

When an issue text and ADR 0023 disagree, the cases follow the ADR, because an accepted ADR ranks above an issue description. Each disagreement is listed under [Points to settle in the review](#points-to-settle-in-the-review).

## How to read a row

- **ID**: stable, never reused. Prefix `P3`, then the threat group, then a number.
- **Preconditions**: the state that must exist before the action. Names come from the fixture list below.
- **Request or action**: what the tester does.
- **Expected denial or outcome**: what must happen, including what must not be written.
- **Inv**: the existing invariant the case protects.
- **Owner**: the issue that owns the implementation the case exercises. When the domain rule already shipped in [#133] and only the HTTP route is missing, the owner is the issue that adds the route.
- **Evidence**: one of three kinds.

| Kind | Meaning |
| --- | --- |
| **Automated fixture** | A test on `main` already proves the case with synthetic data. The row names the test. It proves the rule at the layer the test runs on (domain, service or database), which is usually below HTTP. |
| **Preparation** | The case is written down and nothing proves it yet, because the code it needs is not built. The owner issue is expected to add the automated fixture. |
| **Real stack** | The case can only be proved against the running integrated stack (browser, proxy, real logs). Nothing is recorded yet. [#149] runs it. |

[#149] runs every row again on one recorded commit and reports PASS, FAIL or BLOCKED for each ID, with the channel it used. An automated fixture does not replace that run. The channel follows the action in the row:

- **HTTP**: a row whose action is a request to a route runs over real HTTP through the proxy. This is most rows.
- **Database**: a row whose action names the runtime database role (P3-RLS-01 to P3-RLS-04, P3-HOOK-13 and the direct insert in P3-DUP-05) runs on a database connection as `dokene_runtime` against the same stack.
- **In process**: a row whose action is an internal call with no route (P3-HOOK-12 and P3-SEC-09) runs as a test built from the same commit.

## Fixture set

All data is invented. No real customer, phone number or credential is used.

### Tenants and actors

| Name | Tenant | Role | Notes |
| --- | --- | --- | --- |
| `A-OWNER` | A (`QA Café Norte`) | `OWNER` | seed user `testuser` |
| `A-ADMIN` | A | `ADMIN` | the local seed has no `ADMIN` user today, see the review points |
| `A-OPERATOR` | A | `OPERATOR` | seed user `testoperator` |
| `A-VIEWER` | A | `VIEWER` | seed user `testviewer` |
| `B-OWNER` | B (`QA Café Sur`) | `OWNER` | |
| `B-OPERATOR` | B | `OPERATOR` | |
| `X-NONE` | none | none | signed in, member of no tenant |

### Data

| Name | Meaning |
| --- | --- |
| `CA1`, `CB1` | Customer in tenant A, customer in tenant B. Each is `ACTIVE`, has one phone contact with `GRANTED` WhatsApp consent, no do not contact flag, and a follow up that is `DUE`. |
| `MA-PENDING`, `MA-APPROVED`, `MA-SENT` | Messages for `CA1` in tenant A in that state. Only one is open at a time, so each case builds the one it needs. |
| `MA-UNKNOWN` | Message in tenant A in `SENDING` with `outcomeUnknown=true`. |
| `MB-PENDING`, `MB-SENT` | Messages for `CB1` in tenant B. |
| `IA`, `IB` | WhatsApp integration of tenant A and of tenant B, with phone number ids `PN-A` and `PN-B`. |
| `CANARY-TOKEN`, `CANARY-APP-SECRET`, `CANARY-VERIFY` | Invented secret values, unique strings that are easy to search for. |
| `CANARY-PHONE`, `CANARY-BODY` | An invented recipient phone and message body, also unique strings. |

### Shared expectations

- A **denied** request means: HTTP `403`, body `application/problem+json` with exactly `type`, `title`, `status` and `code` where `code` is `FORBIDDEN`, one `AUTHORIZATION_DENIED` audit row that names the missing permission, and no business row, event row or idempotency key row.
- A **refused** request means: HTTP `409` with the closed code named in the row, and nothing written at all (no event, no audit, no idempotency key).
- **Not found** means: HTTP `404` with code `NOT_FOUND`, and the response is the same as for a random UUID that exists nowhere.
- No error body ever carries `detail`, free text, a provider message or an identifier.

## Role and permission checklist

This is ADR 0023 section 5 as a checklist. It is derived from `TenantRolePermissions` as it is today. `OPERATOR` holds `MESSAGE_APPROVE` and `MESSAGE_SEND` and does not hold `INTEGRATION_MANAGE`. There is no mandatory second approver in Phase 3, and no case below expects one.

Each row is one case with four checks, one per role in tenant A. `allow` means the request succeeds. `deny` means a denied request as defined above.

| ID | Operation | Permission | OWNER | ADMIN | OPERATOR | VIEWER | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| P3-PERM-01 | `GET /api/messages`, `GET /api/messages/{id}`, `GET /api/messages/{id}/events` | `MESSAGE_READ` | allow | allow | allow | allow | 4 | [#136] | Preparation |
| P3-PERM-02 | `POST /api/customers/{CA1}/messages` (submit, T1) | `MESSAGE_DRAFT` | allow | allow | allow | deny | 4, 24 | [#136] | Automated fixture for the VIEWER denial at the service: `OutboundMessagingIntegrationTest.refusesSubmitWhenForbiddenStaleUnmappedOrCrossTenantWithoutLeavingRows`. Route is preparation. |
| P3-PERM-03 | `POST /api/messages/{MA-PENDING}/cancellations` (T4) | `MESSAGE_DRAFT` | allow | allow | allow | deny | 4, 24 | [#136] | Preparation |
| P3-PERM-04 | `POST /api/messages/{MA-PENDING}/approvals` with `APPROVE` or `REJECT` (T2, T3) | `MESSAGE_APPROVE` | allow | allow | allow | deny | 4, 8, 24 | [#136] | Preparation |
| P3-PERM-05 | `POST /api/messages/{MA-APPROVED}/cancellations` (T5) | `MESSAGE_APPROVE` | allow | allow | allow | deny | 4, 24 | [#136] | Preparation |
| P3-PERM-06 | `POST /api/messages/{MA-APPROVED}/sends` (T6) | `MESSAGE_SEND` | allow | allow | allow | deny | 4, 24 | [#138] | Preparation |
| P3-PERM-07 | `POST /api/messages/{MA-UNKNOWN}/resolutions` (T12) | `INTEGRATION_MANAGE` | allow | allow | deny | deny | 4, 9 | [#138] | Preparation |
| P3-PERM-08 | `GET /api/templates` | `TEMPLATE_READ` | allow | allow | allow | allow | 4 | [#135] | Preparation |
| P3-PERM-09 | `PUT /api/templates/{templateIntent}` | `TEMPLATE_WRITE` | allow | allow | allow | deny | 4 | [#135] | Preparation |
| P3-PERM-10 | `GET /api/integrations/whatsapp` | `INTEGRATION_READ` | allow | allow | allow | allow | 4, 6 | [#134] | Preparation |
| P3-PERM-11 | `PUT /api/integrations/whatsapp` | `INTEGRATION_MANAGE` | allow | allow | deny | deny | 4, 6 | [#134] | Preparation |
| P3-PERM-12 | `PUT /api/integrations/whatsapp/outbound` (tenant kill switch) | `INTEGRATION_MANAGE` | allow | allow | deny | deny | 4, 8 | [#144] | Preparation |
| P3-PERM-13 | Read the audit history of a message | `AUDIT_READ` | allow | allow | deny | deny | 11 | [#136] | Preparation |

The role map itself is already locked by an automated fixture: `TenantRolePermissionsTest` (`ownerHasAllPermissions`, `adminHasExplicitAdministrativeAndOperationalPermissions`, `operatorHasOperationalPermissionsOnly`, `viewerHasReadOnlyPermissionsOnly`). The rows above are about each route asking for the right permission, which that test cannot prove.

More role cases:

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-PERM-14 | `CA1` due, no open message | `A-OPERATOR` alone submits, approves and sends the same message | All three succeed. This is allowed by ADR 0023 section 2 and is **not** a finding. A tester who reports it as a missing second approver is wrong. | 8, 24 | [#138] | Preparation |
| P3-PERM-15 | `MA-PENDING`. The UI hides the approve control from `A-VIEWER`. | `A-VIEWER` sends the approval request directly with curl, with a valid session and CSRF token | Denied. Hiding a control is not the boundary. | 4, 22 | [#136] | Preparation |
| P3-PERM-16 | `MA-PENDING` | `A-VIEWER` posts an approval with no `Idempotency-Key` and no `If-Match` | Denied with `403`, not `400`. The permission check runs before input validation, and the denial is audited. | 4, 12 | [#136] | Preparation. The same rule is proved for older routes by `AuthorizationBeforeValidationIntegrationTest`. |
| P3-PERM-17 | `MA-APPROVED`. `A-OPERATOR` has an open session. `A-OWNER` changes that member to `VIEWER`. | `A-OPERATOR` requests a send in the same session | Denied. The role is read at execution, not at login. No attempt row, no provider call. | 4, 24 | [#138] | Preparation |
| P3-PERM-18 | `MA-APPROVED`. `A-OWNER` revokes the membership of `A-OPERATOR`. | The revoked member requests a send in the old session | `403`, no attempt row, no provider call. | 1, 4 | [#138] | Preparation |
| P3-PERM-19 | none | `X-NONE` calls any Phase 3 `/api` route with `X-Tenant-Id` of tenant A | `403`, nothing revealed about tenant A. | 1, 14 | [#136] | Preparation. Proved for older routes by `BffTenantBoundarySecurityIntegrationTest.authenticatedIdentityWithoutMembershipFailsClosedOnTenantApis`. |

## IDOR and RLS

IDOR means asking for another tenant's object by its id. RLS is PostgreSQL row level security, the database rule that hides other tenants' rows even when the application forgets a filter.

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-IDOR-01 | `MB-PENDING` exists | `A-OPERATOR`, tenant A selected, `GET /api/messages/{MB-PENDING}` and its `/events` | Not found. Same response as a random UUID. | 1, 2 | [#136] | Preparation |
| P3-IDOR-02 | `MB-PENDING` exists | `A-OPERATOR` lists `GET /api/messages?customerId={CB1}` and pages through the unfiltered list | No tenant B item on any page. | 1, 2 | [#136] | Preparation |
| P3-IDOR-03 | `CB1` exists | `A-OPERATOR` posts a submit to `/api/customers/{CB1}/messages` | Not found. No message row, no event, no idempotency key row. | 1, 2, 24 | [#136] | Automated fixture at the service: `OutboundMessagingIntegrationTest.refusesSubmitWhenForbiddenStaleUnmappedOrCrossTenantWithoutLeavingRows`. Route is preparation. |
| P3-IDOR-04 | `MB-PENDING` exists | `A-OPERATOR` posts an approval, a rejection and a cancellation against `MB-PENDING` | Not found each time. `MB-PENDING` keeps its version and has no new event. | 1, 2, 24 | [#136] | Automated fixture at the service for the foreign cancellation only: `OutboundMessagingIntegrationTest.rejectsAndCancelsThroughTheirOwnRecordsAndRefusesStaleOrForeignCommands`. The foreign approval and rejection, and the route, are preparation. |
| P3-IDOR-05 | A tenant B message in `APPROVED`, and one in `SENDING` with unknown outcome | `A-ADMIN` posts a send and a resolution against them | Not found. No attempt row, no provider call. | 1, 2, 4 | [#138] | Preparation |
| P3-IDOR-06 | `A-OPERATOR` has no membership in tenant B | `A-OPERATOR` sends `X-Tenant-Id` of tenant B on any Phase 3 route | `403`. The tenant comes from a membership the server verified, never from the header alone. | 1, 2 | [#136] | Preparation. Proved for older routes by `TenantIsolationSecurityIntegrationTest.acceptsOnlyTheServerVerifiedTenantSelectorAndClearsTheRequestContext`. |
| P3-IDOR-07 | `CA1` and a second tenant A customer `CA2`, and `CB1` | `A-OPERATOR` submits for `CA1` with a `contactId` that belongs to `CA2`, then with one that belongs to `CB1` | Not found both times, the same response as for a random UUID (ADR 0023 section 4.4). No message row. A contact of another customer cannot become the recipient, and the response does not say whether the contact exists. | 2, 17, 24 | [#136] | Preparation |
| P3-IDOR-08 | `A-OPERATOR` submitted with key `K1` | `B-OPERATOR` submits for `CB1` with the same key `K1` | Handled as a new request in tenant B. It never returns or touches the tenant A message. | 2, 24 | [#136] | Preparation. `OutboundMessagingGuardIntegrationTest.anotherTenantSeesNoMessagingRowsAndCannotReplayTheKey` proves only that tenant B sees no tenant A rows and gets not found when it reuses `K1` against `CA1`. It never submits for `CB1`, so the tenant B success is not proved. |
| P3-IDOR-09 | `IB` uses `PN-B` | `A-ADMIN` reads `GET /api/integrations/whatsapp`, then puts `PN-B` into the tenant A integration | The read shows only tenant A data. The write is refused with a closed code, `PN-B` stays mapped to tenant B only, and the response does not show whether tenant B has an integration. | 2, 7 | [#134] | Preparation |
| P3-IDOR-10 | A template mapping exists in tenant B | `A-OPERATOR` reads and writes templates | Only the tenant A catalogue is read and changed. The tenant B catalogue version does not move. | 1, 2 | [#135] | Preparation |
| P3-RLS-01 | Messaging rows exist in tenants A and B | Connect as the runtime database role. Query every messaging table from migration V18 with no tenant capability, then with the tenant A capability and no `WHERE tenant_id` | No capability: zero rows. Tenant A capability: only tenant A rows. Forced RLS is on and the runtime role has DML grants only. | 2, 10 | [#133] | Automated fixture for forced RLS, the policy names and the runtime grants only: `TenantIsolationSecurityIntegrationTest.outboundMessagingTablesCarryForcedRlsLeastPrivilegeGrantsAndTheOpenMessageBackstop`. It does not seed both tenants or run the visibility queries, so the zero rows and tenant A only results are preparation. |
| P3-RLS-02 | Same | As the runtime role, present a forged capability and an expired capability for tenant B | Neither can read nor write any messaging table. | 2, 10 | [#133] | Automated fixture: `OutboundMessagingGuardIntegrationTest.forgedAndExpiredTenantCapabilitiesCanNeitherReadNorWriteAnyMessagingTable` |
| P3-RLS-03 | `MA-PENDING` | As the runtime role with a valid tenant A capability, try to update the stored body or recipient, and to update or delete an event row | Refused by the database. What was submitted for approval cannot be rewritten, and the event log is append only. | 9, 11, 24 | [#133] | Automated fixture for the body and recipient rewrite: `OutboundMessagingGuardIntegrationTest.theOwningTenantCannotRewriteWhatWasSubmittedForApprovalAsTheRuntimeRole`. For the event log, `TenantIsolationSecurityIntegrationTest.outboundMessagingTablesCarryForcedRlsLeastPrivilegeGrantsAndTheOpenMessageBackstop` asserts the runtime role holds only `SELECT` and `INSERT` on `outbound_message_events`. No test attempts the event update or delete, so that attempt is preparation. |
| P3-RLS-04 | The tables for integrations, provider templates and webhook events exist | Repeat P3-RLS-01 and P3-RLS-02 on `tenant_messaging_integrations`, `tenant_provider_templates` and `provider_webhook_events` | Same result as P3-RLS-01 and P3-RLS-02. | 2, 10 | [#134], [#135], [#139] | Preparation |

## CSRF

CSRF means another website making the signed in browser send a request the user did not intend. The defence is the `X-CSRF-TOKEN` header on every unsafe method under `/api`.

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-CSRF-01 | `A-OWNER` session, the right state for each route | Send each unsafe Phase 3 route with a valid session and **no** `X-CSRF-TOKEN`: submit, approvals, cancellations, sends, resolutions, `PUT` templates, `PUT` integration, `PUT` outbound | `403` each time. Nothing written, no provider call. | 15 | [#136], [#138], [#134], [#135], [#144] | Preparation. Proved for older routes by `BffTenantBoundarySecurityIntegrationTest.csrfEnforcementOnRepresentativeBusinessMutations`. |
| P3-CSRF-02 | Two sessions, `A-OWNER` and `A-OPERATOR` | Repeat P3-CSRF-01 with the token taken from the other session, then with a random token | `403` each time. Nothing written. | 15 | [#136], [#138] | Preparation |
| P3-CSRF-03 | `A-OWNER` signed in in a real browser, `MA-APPROVED` | Open a page on another origin that posts a form and runs a `fetch` to `/api/messages/{MA-APPROVED}/sends` | No send happens. The cookie is `SameSite=Lax` and the origin is not allowed, so the request fails or is rejected. No attempt row. | 15 | [#140] | Real stack |
| P3-CSRF-04 | Webhook route deployed | Post to `/webhooks/whatsapp` with no CSRF token and a valid signature, then post to `/api/messages/{id}/sends` with no CSRF token | The webhook is accepted on its signature alone. The `/api` request still gets `403`. The CSRF exemption covers the webhook path only. | 7, 15 | [#139] | Preparation |
| P3-CSRF-05 | Webhook route deployed | Call `/webhooks/whatsapp` with and without a session cookie, and try sibling paths such as `/webhooks/other` and `/webhooks/whatsapp/../../api/session` | No `Set-Cookie` in any webhook response, no redirect to the login page, and no path under `/webhooks` reaches an `/api` handler. | 7, 15 | [#139] | Preparation for the filter chain. Real stack for the path handling through the nginx proxy. |

## Edited or stale approval

ADR 0023 keeps a submitted message immutable. There is no server side draft and no edit route. To change the text, the operator cancels and submits a new message, which needs its own approval. A stale approval is one given against an old version of the message.

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-APPR-01 | `MA-PENDING` at version 1. The tester reads it and holds ETag `"1"`. A second member then rejects it, so it is `REJECTED` at version 2. | The tester approves with `If-Match: "1"` | Refused, `STALE_VERSION`, because the version check runs before the state check. No event, no audit, no approval row. | 9, 24 | [#136] | Automated fixture at the service for the version rule: `OutboundMessagingGuardIntegrationTest.approveOneVersionBehindIsRefusedAsStaleAndWritesNoEventAndNoAudit`. That test uses a pending message at version 1 and the stale value `"0"`, because a pending message never reaches version 2. The competing transition and the route are preparation. |
| P3-APPR-02 | For `MA-PENDING`, the competing transition of P3-APPR-01. For `MA-APPROVED` (version 2), the tester holds `"1"`. | Reject or cancel one version behind | Refused, `STALE_VERSION`. Nothing written. | 9, 24 | [#136] | Automated fixture at the service: `rejectOneVersionBehindIsRefusedAsStaleAndWritesNoEventAndNoAudit`, `cancelOneVersionBehindIsRefusedAsStaleAndWritesNoEventAndNoAudit` and `cancelOfAnApprovedMessageOneVersionBehindIsRefusedAsStaleAndWritesNoEventAndNoAudit` in `OutboundMessagingGuardIntegrationTest` |
| P3-APPR-03 | `MA-PENDING` | Approve with no `If-Match`, with `If-Match: *`, with a weak value `W/"1"` and with a list `"1", "2"` | `400`, code `INVALID_INPUT`. Nothing written. | 9, 24 | [#136] | Preparation |
| P3-APPR-04 | `MA-PENDING` and `MA-APPROVED` | Try `PUT` and `PATCH` on `/api/messages/{id}` with a new `body`, `contactId` or `status` | No such route. `404` or `405`. The stored body, recipient and status do not change. | 9, 24 | [#136] | Preparation. The database side is P3-RLS-03. |
| P3-APPR-05 | `MA-PENDING` | Post an approval whose JSON also carries `body`, `recipientPhone`, `status: "SENT"` and `contactId` | The extra fields never change the message. Only T2 happens, or the request is rejected with `400`. No endpoint accepts a target status. | 9, 24 | [#136] | Preparation |
| P3-APPR-06 | `M1` approved for `CA1` | Cancel `M1`, submit `M2` with different text, request a send of `M2` without approving it | Refused, `INVALID_TRANSITION`. The approval of `M1` does not carry over to `M2`. No attempt row. | 8, 24 | [#138] | Automated fixture at the domain: `OutboundMessageTransitionMatrixTest.everyStateFlagAndCommandCellMatchesTheAdrTable`. Route is preparation. |
| P3-APPR-07 | `CA1` policy at version 7, tester holds 6 | Submit with `If-Match: "6"` | Refused, `STALE_VERSION`. No rows. A draft made against an old evaluation cannot be submitted. | 22, 24 | [#136] | Automated fixture at the service: `OutboundMessagingIntegrationTest.refusesSubmitWhenForbiddenStaleUnmappedOrCrossTenantWithoutLeavingRows` |
| P3-APPR-08 | `MA-PENDING` | Approve after it was rejected, approve twice with different keys, reject after approval, cancel after send | Refused each time, `INVALID_TRANSITION`. | 9, 24 | [#136] | Automated fixture at the domain: `OutboundMessageTransitionMatrixTest.everyStateFlagAndCommandCellMatchesTheAdrTable` |
| P3-APPR-09 | `MA-PENDING` for a contact of `CA1` | Correct the customer phone number, which replaces the contact, then approve | Refused, `CONTACT_NOT_OWNED`. No approval row and no `MESSAGE_APPROVED` audit. The message can be rejected so the customer can be messaged on the corrected number. | 16, 17, 24 | [#136] | Automated fixture at the service: `phoneCorrectionAfterSubmitSucceedsAndStrandsThePendingMessage` and `strandedMessageCanBeRejectedAndTheCustomerMessagedAgainOnTheCorrectedPhone` in `OutboundMessagingIntegrationTest` |
| P3-APPR-10 | `MA-APPROVED`, then the template mapping for its intent is disabled. A mapping changed to another provider template but still enabled is not covered, see the review points. | Request a send | Refused, `TEMPLATE_NOT_MAPPED`. No attempt row. | 4, 24 | [#135] | Preparation |
| P3-APPR-11 | `MA-APPROVED`, then `IA` is disabled or its `secretRef` or `PN-A` is changed | Request a send | Refused with `INTEGRATION_DISABLED` when disabled. After a change, the send uses only the current configuration and never a cached credential. | 4, 6, 24 | [#134] | Preparation |
| P3-APPR-12 | `MA-UNKNOWN`. `A-ADMIN` holds its ETag. A delivery report then moves the message. | `A-ADMIN` posts a resolution with the old ETag | Refused, `STALE_VERSION`. Versions advance on system and webhook transitions too. | 9, 24 | [#139] | Preparation |

## Revoked consent

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-CONS-01 | Customer archived, or do not contact set, or consent not granted, or follow up not due | Submit a message | Refused with the matching code. No rows. | 5, 17, 19, 24 | [#136] | Automated fixture at the service: `OutboundMessagingGuardIntegrationTest.submitRefusesArchivedDoNotContactUnconsentedAndNotYetDueCustomersWithoutRows` |
| P3-CONS-02 | `MA-PENDING`, then consent is revoked | Approve | Refused, `NO_CONTACT_CONSENT`. Nothing written. | 5, 17, 24 | [#136] | Automated fixture at the service: `OutboundMessagingGuardIntegrationTest.approveReChecksConsentAndDoNotContactAndWritesNothingWhenRefused` |
| P3-CONS-03 | `MA-PENDING`, then do not contact is set | Approve | Refused, `DO_NOT_CONTACT`. Nothing written. | 5, 17, 24 | [#136] | Automated fixture, same test as P3-CONS-02 |
| P3-CONS-04 | `MA-APPROVED`, then consent is revoked | Request a send | Refused, `NO_CONTACT_CONSENT`. No attempt row, no provider call, message stays `APPROVED`. | 5, 17, 24 | [#138] | Preparation |
| P3-CONS-05 | `MA-APPROVED`, then do not contact is set | Request a send | Refused, `DO_NOT_CONTACT`. No attempt row, no provider call. | 5, 17, 24 | [#138] | Preparation |
| P3-CONS-06 | `MA-APPROVED`, then `CA1` is archived | Request a send | Refused, `CUSTOMER_ARCHIVED`. No attempt row, no provider call. | 5, 19, 24 | [#138] | Preparation |
| P3-CONS-07 | `MA-APPROVED`, then a purchase or manual follow up makes `CA1` no longer due | Request a send | Refused, `FOLLOW_UP_INELIGIBLE`. No attempt row. | 19, 20, 24 | [#138] | Preparation |
| P3-CONS-08 | `MA-APPROVED` | Fire a consent revocation and a send at the same moment, many times | The two serialize on the customer lock. Either the send committed first, or it is refused. The provider is never called after the revocation committed. | 5, 24 | [#138] | Preparation. The real stack run needs fault injection, [#149]. |
| P3-CONS-09 | `CA1` has two contacts, consent `GRANTED` on one and not on the other | Submit with the contact that has no consent | Refused, `NO_CONTACT_CONSENT`. Consent on one contact never covers another. | 17 | [#136] | Preparation |
| P3-CONS-10 | `MA-PENDING` or `MA-APPROVED`, then consent is revoked | Reject or cancel the message | Succeeds. Reject and cancel check only state and version by design, so an operator can always clear a message that may no longer be sent. This is **not** a finding. | 9, 24 | [#136] | Preparation |

## Replay or duplicate send

The goal is that an approved message reaches the provider at most once. HTTP idempotency (the `Idempotency-Key` header) and provider idempotency (one attempt row committed before the call) are separate, and both are tested.

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-DUP-01 | `MA-APPROVED`, local provider records calls | Send with key `K`, then repeat the exact request | First `201`, replay `200` with the current representation. One attempt row, one provider call, one `MESSAGE_SEND_REQUESTED` audit. | 4, 24 | [#138] | Preparation |
| P3-DUP-02 | `MA-APPROVED` | Two sends at the same moment with different keys (the double click) | Exactly one attempt row and one provider call. The other request is refused with `409`. | 4, 24 | [#138] | Preparation |
| P3-DUP-03 | Key `K` used for a send of `M1` | Reuse `K` for a different message, then for the same message with a different payload | Refused, `IDEMPOTENCY_KEY_REUSED`. Nothing written. | 4, 24 | [#138] | Preparation for sends. The store rule has a service test in `OutboundMessagingIntegrationTest` and `RequestFingerprintTest`. |
| P3-DUP-04 | `CA1` due, no open message | Two submits at the same moment with the same key | One message and one key row. | 4, 24 | [#136] | Automated fixture: `OutboundMessagingGuardIntegrationTest.concurrentSubmitsWithTheSameKeyProduceOneMessageAndOneKeyRow` |
| P3-DUP-05 | `CA1` due, no open message | Two submits at the same moment with different keys, then an insert of a second open row straight into the table | One open message. The database index refuses the second row even behind the service. | 4, 24 | [#133] | Automated fixture: `OutboundMessagingIntegrationTest.concurrentSubmitsForOneCustomerLeaveExactlyOneOpenMessage` and `OutboundMessagingGuardIntegrationTest.openMessageIndexRefusesASecondOpenRowInsertedBehindTheService` |
| P3-DUP-06 | A submit with key `K` succeeded, then the message was approved | Replay the submit with `K` | `200` with the message as it is now (`APPROVED`, later ETag). No second message, no new event, no new audit. | 4, 24 | [#136] | Automated fixture at the service: `OutboundMessagingIntegrationTest.replayAfterTheMessageAdvancedReturnsTheCurrentRepresentationAndTheChildRecordOfTheFirstRequest` |
| P3-DUP-07 | `MA-APPROVED`, the provider fixture times out after the request left | Send once, then send again with a new key | First send leaves the message in `SENDING` with `outcomeUnknown=true` and audit `MESSAGE_SEND_OUTCOME_UNKNOWN`. The second send is refused. No second provider call by any path. | 4, 24 | [#138] | Automated fixture at the domain: `OutboundMessageTransitionMatrixTest.unknownOutcomeSetsTheFlagStoresTheIdAndBlocksFurtherProviderCalls`. Route is preparation. |
| P3-DUP-08 | `MA-UNKNOWN` | `A-ADMIN` posts a resolution with `outcome: SENT`, then with `outcome: FAILED` | `SENT` is rejected with `400`. `FAILED` moves the message to `FAILED`. A message is never marked sent without a provider message id. | 9, 24 | [#138] | Automated fixture at the domain: `OutboundMessageInvariantsTest.resolveFailedOnlyAppliesToAnUnknownOutcomeAndRecordsAMemberEvent`. Route is preparation. |
| P3-DUP-09 | `MA-APPROVED`, the provider fixture fails transiently every time | Send three times with new keys, then a fourth time | After the third failure the message is `FAILED`. The fourth send is refused. At most three provider calls. | 4, 24 | [#138] | Automated fixture at the domain: `OutboundMessageTransitionMatrixTest.transientFailureAtTheAttemptLimitLandsOnFailed`. Route is preparation. |
| P3-DUP-10 | `MA-SENT` for `CA1` | Submit and try to send a second message to `CA1` before the cadence moves on | Refused, `MESSAGE_ALREADY_OPEN` or `CONTACT_FREQUENCY_EXCEEDED`. See also [#198] for how long a message may stay open. | 4, 19, 24 | [#138] | Preparation |
| P3-DUP-11 | `MA-APPROVED` | Send with no `Idempotency-Key`, then with a key of 129 characters, then with a key that holds a space | `400`, `INVALID_INPUT`. No attempt row. | 4, 24 | [#138] | Preparation |
| P3-DUP-12 | `MA-APPROVED`. Stop the backend after the attempt row commits and before the provider answers. | Restart the backend and wait | No automatic resend. The message is not sent again without a human action. | 4, 24 | [#138] | Real stack, with fault injection in [#149] |
| P3-DUP-13 | Tenant A and tenant B each have approved messages | Tenant A requests more sends in one minute than `dokene.messaging.send-rate-limit.per-tenant-per-minute` | `429` with `Retry-After` and code `RATE_LIMITED` for tenant A. Tenant B is not slowed down. | 4 | [#138] | Preparation |

## Forged webhook

The webhook route is the only unauthenticated `POST` in the application. The signature over the raw bytes is the trust decision, and the tenant comes only from the stored phone number id.

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-HOOK-01 | `MA-SENT` with a known provider message id | Post a well formed `delivered` event with no `X-Hub-Signature-256` | `403`. Body not parsed, nothing stored, message unchanged. | 7 | [#143] | Preparation |
| P3-HOOK-02 | Same | Post the event signed with a wrong secret, then a correctly signed event with one byte changed, then the same JSON with different whitespace | `403` each time. The check is on the raw bytes. | 7 | [#143] | Preparation, with the signed fixtures from [#145] |
| P3-HOOK-03 | Same | Send a malformed signature header: `sha1=` prefix, no prefix, wrong length, not hexadecimal, empty | `403`. No error leaks which part was wrong. | 7 | [#143] | Preparation |
| P3-HOOK-04 | `MA-SENT` in tenant A, `MB-SENT` in tenant B | Post a validly signed event for `PN-A` whose payload also carries a `tenantId` field with the tenant B id | The tenant is resolved from the stored `PN-A` only. Tenant B is untouched. | 2, 7, 24 | [#139] | Preparation |
| P3-HOOK-05 | Same | Post a validly signed event for `PN-A` that names the provider message id of `MB-SENT` | `200`, `MB-SENT` does not change, the event is counted as dropped and its payload is not stored. | 2, 7, 24 | [#139] | Preparation |
| P3-HOOK-06 | `MA-SENT` | Post the same validly signed `delivered` event twice | The first moves the message to `DELIVERED`. The second returns `200` and changes nothing: no state change, no second audit row. | 4, 7, 24 | [#139] | Preparation |
| P3-HOOK-07 | A message that is `READ`, and one that is `DELIVERED` | Post `delivered` after `read`, `sent` after `delivered`, and `failed` after `delivered` | Each is recorded as a non applied event. The state never moves backwards and a late failure does not erase a delivery. | 9, 24 | [#139] | Automated fixture: `OutboundMessageDeliveryReportMatrixTest.everyStateAndReportCellMatchesTheDeliveryReportTable` and `DefaultDeliveryStatusSinkTest.recordsAnOutOfOrderReportAsANonAppliedEventWithAVersionBumpAndNoAudit` |
| P3-HOOK-08 | none | Post a validly signed event for a phone number id no tenant uses, and one for an unknown provider message id | `200` both times, same response as a normal event, nothing stored, dropped metric counted. The route reveals nothing. | 7 | [#139] | Preparation. The sink side is covered by `DefaultDeliveryStatusSinkTest.returnsNotFoundForAnUnknownProviderMessageIdWithoutLocking`. |
| P3-HOOK-09 | Verify token is `CANARY-VERIFY` | `GET /webhooks/whatsapp` with a wrong `hub.verify_token`, then with the right one | Wrong: `403`. Right: `200` with only `hub.challenge` as `text/plain`. The token is never echoed or logged. | 6, 7 | [#143] | Preparation |
| P3-HOOK-10 | none | Post a validly signed body that does not match the expected schema, and a body far larger than a real event | `400` for the wrong schema. The oversized body is cut off by a size limit. No provider call and no long processing. | 7 | [#143] | Preparation |
| P3-HOOK-11 | `A-OWNER` session cookie | Post an unsigned event to the webhook with that cookie attached | `403`. A session cookie proves nothing on this route. | 7, 15 | [#139] | Preparation |
| P3-HOOK-12 | none | Call the delivery sink from a member context, and with no provider context | Refused. Delivery reports are applied only under a trusted provider context that names the tenant. | 7, 13, 24 | [#133] | Automated fixture: `OutboundMessagingIntegrationTest.deliverySinkRequiresTheProviderContextAndRefusesAMemberContext`, `DefaultDeliveryStatusSinkTest.refusesToRunWithoutAProviderContext` and `refusesToRunWhileAMemberContextIsBound` |
| P3-HOOK-13 | none | As the runtime role, try to append a provider attributed audit event with a forged capability, then with an expired one | Refused by the database. | 11, 13, 24 | [#133] | Automated fixture: `OutboundMessagingGuardIntegrationTest.forgedAndExpiredProviderCapabilitiesCannotAppendTheProviderAttributedAuditEvent` |
| P3-HOOK-14 | `MA-SENT` | Post a validly signed event whose timestamp is far in the future, then one from before the message existed | The stored delivery time is bounded to the allowed window and the follow up anchor never moves backwards. | 7, 24 | [#139] | Automated fixture: `OutboundMessagingIntegrationTest.deliveryReportTimesAreBoundedAndTheCadenceAnchorNeverMovesBackwards` and the bounding tests in `OutboundMessageDeliveryReportMatrixTest` |

## Secret exposure

A secret reference is a name that points to a credential. It is never the credential. The canary values make leaks easy to find: search for the exact string.

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-SEC-01 | `IA` configured, the token behind its reference is `CANARY-TOKEN` | Each of the four roles reads `GET /api/integrations/whatsapp` | The body has `secretRef` and never a secret value. `CANARY-TOKEN` is absent from the body and the headers. | 6, 15 | [#134] | Preparation |
| P3-SEC-02 | `A-ADMIN` | Put an integration whose `secretRef` is a raw token, then a URL, then a file path, then `../` | Rejected with `400`. The value is not stored, not resolved and not logged. | 6, 7 | [#142] | Preparation |
| P3-SEC-03 | Tenant B has a valid secret reference | `A-ADMIN` sets the tenant A `secretRef` to the tenant B reference, then to a reference of the wrong purpose (the app secret) | Fails closed. Tenant A cannot send with a tenant B credential. The integration reports a safe category, not the reason in detail. | 2, 6 | [#142] | Preparation |
| P3-SEC-04 | `IA` points to a reference that does not resolve | Request a send | No provider call. Safe refusal or safe failure category. No stack trace, no reference value in the response. | 6 | [#142] | Preparation |
| P3-SEC-05 | The provider fixture answers with an error that echoes `CANARY-TOKEN`, `CANARY-PHONE` and `CANARY-BODY` | Request a send | The result is a closed `FailureCategory` only. None of the three canaries appears in the HTTP response, the event log, the audit metadata or the application log. | 6, 21, 24 | [#137] | Preparation, with the hostile fixtures from [#145] |
| P3-SEC-06 | none | Run submit, approve, send and a delivery report with the canary phone and body | The application log and the audit metadata hold no body, phone, note or provider message id. | 6, 13, 24 | [#133] | Automated fixture at the service for the messaging package log only: `OutboundMessagingIntegrationTest.submitsApprovesSendsAndAppliesDeliveryWhileAnchoringTheFollowUpAndKeepingLogsClean`. It inspects the `io.github.stevdrey.dokene.messaging` logger, so logs from other packages during this journey, and the audit metadata contents, are preparation. |
| P3-SEC-07 | Integrated stack, canaries configured, one full journey done including a failed send and a forged webhook | Search the backend log, the proxy log, the audit table, the metrics output and the browser screens for every canary | No canary secret anywhere. The canary phone and body appear only where a member with `MESSAGE_READ` reads the message. | 6, 13, 24 | [#149] | Real stack |
| P3-SEC-08 | Integrated stack, `A-OWNER` in a real browser on the integration screen | Inspect the network responses, local storage, session storage, cookies and the JavaScript bundle. Watch for any request to a host other than Dokene. | No credential in the browser. The frontend never calls Meta. | 15, 22 | [#146] | Real stack |
| P3-SEC-09 | A secret value loaded through the resolver | Print the holder object, force an exception that includes it, and log it at debug level | The value is redacted every time. | 6 | [#142] | Preparation |
| P3-SEC-10 | Any refused or denied Phase 3 request | Read the error body | Exactly `type`, `title`, `status`, `code`. No `detail`, no identifiers, no provider text. | 6, 24 | [#136] | Preparation |
| P3-SEC-11 | Metrics enabled, a journey in tenants A and B done | Read the metric names and tags | No tenant, customer, actor, message or correlation id in any tag. | 21 | [#138] | Preparation |

## Emergency disable

There are two switches. The global one is a deployment property, `dokene.messaging.outbound.enabled`, off by default, with no API. The tenant one is `PUT /api/integrations/whatsapp/outbound`. Both are read at send time.

| ID | Preconditions | Request or action | Expected denial or outcome | Inv | Owner | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-KILL-01 | Fresh deployment, the global property not set, `MA-APPROVED` | Request a send | Refused, `OUTBOUND_DISABLED_GLOBALLY`. No attempt row, no provider call, message stays `APPROVED`. Off is the default. | 4, 8 | [#144] | Preparation |
| P3-KILL-02 | Global switch on, tenant A switch off, tenant B switch on | Tenant A requests a send, tenant B requests a send | Tenant A is refused with `OUTBOUND_DISABLED_FOR_TENANT`. Tenant B sends. | 2, 4, 8 | [#144] | Preparation |
| P3-KILL-03 | Global switch off, tenant A switch on | `A-OWNER` requests a send, then tries every Phase 3 route and screen to turn the global switch on | Refused, `OUTBOUND_DISABLED_GLOBALLY`. No API or screen can change the global switch. | 4, 8 | [#144] | Preparation |
| P3-KILL-04 | `MA-APPROVED`, both switches on | `A-ADMIN` turns the tenant switch off, then `A-OPERATOR` requests a send straight away | Refused at once, with no cache delay. The change is audited as `OUTBOUND_KILL_SWITCH_CHANGED`. | 4, 8, 12 | [#144] | Preparation |
| P3-KILL-05 | Both switches on | `A-OPERATOR` and `A-VIEWER` put `{ "enabled": false }` and `{ "enabled": true }` on the tenant switch | Denied. The switch does not change. This is the OPERATOR and VIEWER half of P3-PERM-12. | 4 | [#144] | Preparation |
| P3-KILL-06 | Both tenants have the switch on | `A-ADMIN` tries to turn off the tenant B switch with `X-Tenant-Id` of tenant B | `403`. Tenant B still sends. | 1, 2 | [#144] | Preparation |
| P3-KILL-07 | `MA-APPROVED`. The control state cannot be read (integration row missing, or the lookup fails). | Request a send | Refused. An unreadable switch counts as off. | 4, 8 | [#144] | Preparation |
| P3-KILL-08 | `MA-APPROVED`, switches on, `IA` has `enabled: false` | Request a send | Refused, `INTEGRATION_DISABLED`. | 4 | [#134] | Preparation |
| P3-KILL-09 | A send is refused by a switch, then the switch is turned on again | Request the send again with a new key | The full send policy runs again from the start. Consent, do not contact and eligibility are checked again, so a message approved before the outage is not sent blindly. | 4, 5, 24 | [#144] | Preparation |
| P3-KILL-10 | Integrated stack, sends working | Set `DOKENE_MESSAGING_OUTBOUND_ENABLED=false`, restart, and request sends from both tenants | Every send is refused in both tenants. Delivery reports for messages already sent are still recorded. | 4, 8 | [#149] | Real stack |

## Coverage summary

| Threat from the threat model | Case groups |
| --- | --- |
| Cross tenant data access, IDOR | P3-IDOR, P3-RLS, P3-HOOK-04, P3-HOOK-05, P3-KILL-06 |
| Privilege escalation | P3-PERM, P3-KILL-05 |
| Credential leakage, sensitive data in logs | P3-SEC, P3-HOOK-09 |
| Forged or replayed webhooks | P3-HOOK, P3-CSRF-04, P3-CSRF-05 |
| Unauthorized or duplicate outbound messages | P3-APPR, P3-CONS, P3-DUP, P3-KILL |
| Abuse of the platform for spam | P3-DUP-10, P3-DUP-13, P3-KILL |

Counts by evidence kind are not kept here on purpose. They go stale as issues land. The `Evidence` column of each row is the source.

## Points to settle in the review

These came up while writing the cases. None is recorded as a pass. After the review with Steven, each one that is a real gap becomes a linked defect, and this list is replaced by the links.

1. **Edit route.** [#136] lists an edit endpoint and says editing approved content needs a new review. ADR 0023 section 2 has no edit route and no stored draft. P3-APPR-04 follows the ADR. Either the issue text or the ADR needs to change.
2. **Outbox processor.** [#138] asks for a bounded outbox processor. ADR 0023 runs T6 to T11 inside one request with no scheduler. P3-DUP-01, P3-DUP-02 and P3-DUP-12 follow the ADR.
3. **CSRF error body.** ADR 0023 section 4.4 lists what gets a `FORBIDDEN` problem body, and a missing CSRF token is not in that list. P3-CSRF-01 only expects `403`. Should the body be the problem body or stay empty?
4. **Unknown JSON fields.** P3-APPR-05 accepts either a `400` or silently dropped fields. The contract should pick one.
5. **Integration ownership conflict.** P3-IDOR-09 expects a refusal with a closed code, and the ADR does not name the status or the code for a phone number id already used by another tenant.
6. **No `ADMIN` seed user.** The local seed has `testuser`, `testoperator` and `testviewer`. Every ADMIN check in the role checklist needs a fourth user before [#149] can run.
7. **No invariant names the kill switch or the webhook signature.** The cases map to invariants 4, 7 and 8, which cover them in general words. ADR 0023 (Consequences) already says new invariants should be added when the work lands.
8. **Replay contract issue still open.** [#181] is open, while ADR 0023 was amended for it and P3-DUP-06 has a passing test for the amended behavior. It may only need closing.
9. **How long a message stays open.** P3-DUP-10 depends on [#198]. Until that is decided, a message stuck in `SENT` blocks the customer for an unbounded time.
10. **Approval does not bind a template mapping version.** ADR 0023 section 4.3 step 9 checks only that the mapping for the intent exists and is enabled, and the message stores only `templateIntent`. A mapping changed to another provider template after approval is used for the send without a new approval. P3-APPR-10 covers the disabled case only. Either accept this or amend the contract so approval binds a mapping version.

[#133]: https://github.com/stevdrey/dokene/issues/133
[#134]: https://github.com/stevdrey/dokene/issues/134
[#135]: https://github.com/stevdrey/dokene/issues/135
[#136]: https://github.com/stevdrey/dokene/issues/136
[#137]: https://github.com/stevdrey/dokene/issues/137
[#138]: https://github.com/stevdrey/dokene/issues/138
[#139]: https://github.com/stevdrey/dokene/issues/139
[#140]: https://github.com/stevdrey/dokene/issues/140
[#141]: https://github.com/stevdrey/dokene/issues/141
[#142]: https://github.com/stevdrey/dokene/issues/142
[#143]: https://github.com/stevdrey/dokene/issues/143
[#144]: https://github.com/stevdrey/dokene/issues/144
[#145]: https://github.com/stevdrey/dokene/issues/145
[#146]: https://github.com/stevdrey/dokene/issues/146
[#149]: https://github.com/stevdrey/dokene/issues/149
[#181]: https://github.com/stevdrey/dokene/issues/181
[#198]: https://github.com/stevdrey/dokene/issues/198
