# Issue #140 — T12 message resolution route

## Change

`POST /api/messages/{messageId}/resolutions` is now served, as frozen by ADR 0023 §2 (T12), §3.1 and the §6 role
matrix. It is API only: no resolution UI is part of this change (Phase 3 scope item 10 still needs a design).

- `MessageController.resolve`: `@RequiredPermission(INTEGRATION_MANAGE)`, so OPERATOR and VIEWER are denied, and
  audited, before the headers or body are read (issue 162). The body is read strictly as
  `ResolutionRequest { outcome: FAILED, note? }`. Any other `outcome`, an unknown member or a note over 500 characters
  is `400 INVALID_INPUT`. The contract value is mapped to the domain `ResolutionOutcome` with an exhaustive switch.
  The response is `201` on the first request and `200` on a replay. The body is the message representation, with the
  next version as its `ETag`. Errors are `application/problem+json`.
- `OutboundMessageCommandService.resolve`, in this order:
  1. Checks the permission again, then validates input, `Idempotency-Key` and a strict `If-Match`.
  2. Takes the idempotency lock, then the customer lock, then the message lock, then checks the version.
  3. Re-reads the membership under the customer lock and requires `INTEGRATION_MANAGE`, so a membership revoked after
     the route check is refused.
  4. Calls `OutboundMessage.resolveFailed`. `SENDING` with an unknown outcome becomes `FAILED`/`UNKNOWN` and records a
     member `SEND_FAILED` event. Anything else is `409 INVALID_TRANSITION`.
  5. Writes one `outbound_message_resolutions` row holding the note, the time, the membership and the actor, and the
     `MESSAGE_SEND_RESOLVED` audit row.
  6. Records the idempotency key. The operation is `RESOLUTION`, `record_id` is the resolution id, and the fingerprint
     is `messageId, ifMatch, outcome, note` (spec 0001).

  It never calls the provider.
- Migration `V33__create_outbound_message_resolutions.sql`:
  - Adds the resolution table: 1:1 with the message, forced RLS, runtime `SELECT, INSERT` only, the same policies as
    cancellations.
  - Recreates `ck_audit_shape` and `ck_audit_message_columns` with `MESSAGE_SEND_RESOLVED`.
  - It is additive: no existing row or constraint branch changes meaning.
- Audit: a human resolution is `MESSAGE_SEND_RESOLVED`, a provider failure stays `MESSAGE_SEND_FAILED`.
  `DurableMessageAuditAdapter.resolvedFailed` accepts only a member `SEND_FAILED` event, the domain's mark of T12.
  ADR 0023 (T12 row and the audit vocabulary) and spec 0001 are amended.
- The note is kept on the resolution row only. Like cancellation and approval notes, it is not returned and never
  reaches events, audit or logs.

## Security cases

All tests are in `MessageResolutionIntegrationTest` and run over HTTP against PostgreSQL.

| Case | Test |
| --- | --- |
| P3-PERM-07 allow (OWNER, ADMIN) | `anOwnerResolvesAnUnknownOutcomeAsFailedAndAReplayReturnsTheSameResult`, `anAdminMayResolve` |
| P3-PERM-07 deny (OPERATOR, VIEWER), one `AUTHORIZATION_DENIED` per request, also for malformed input | `operatorsAndViewersAreDeniedAndTheDenialIsAudited` |
| Membership revoked after the route check: refused under the lock, one denial, nothing written | `aMembershipRevokedAfterTheRouteCheckIsRefusedUnderTheLock` |
| P3-APPR-12 (a delivery report makes the held ETag stale) | `anETagMadeStaleByADeliveryReportIsRefused` |
| P3-DUP-08 (`SENT` refused with `400`, `FAILED` applied, replay `200` with the same result and no second resolution row, key reuse `409`) | `anOwnerResolvesAnUnknownOutcomeAsFailedAndAReplayReturnsTheSameResult` |
| P3-IDOR-05, resolution half (tenant B's admin gets `404`, the message is unchanged, no resolution row) | `anotherTenantsMessageIsNotFound` |
| Message whose outcome is known: `409 INVALID_TRANSITION`; stale, weak or missing `If-Match`, missing key, strict body | `aMessageWhoseOutcomeIsKnownCannotBeResolvedAndIfMatchIsStrict` |
| Stored note and resolving member; `MESSAGE_SEND_RESOLVED` attributed to the member, no `MESSAGE_SEND_FAILED` row; no provider call | asserted across the tests above |

Other tests that cover this change:

- `DurableMessageAuditAdapterTest.aMemberResolutionIsAuditedAsResolvedAndASystemFailureIsNot` covers the audit mapping.
- `TenantIsolationSecurityIntegrationTest` covers the new table's forced RLS and least-privilege grants.
- `TenantPersistenceIntegrationTest` covers the schema at V33 and table ownership.
- `RequiredPermissionCoverageTest` and `MessagingContractFixturesTest` (the frozen `contracts/messaging/v1` fixtures,
  unchanged) stay green.

## Effect on the #149 run

The #149 candidate run marked P3-PERM-07, P3-DUP-08, P3-APPR-12 and the resolution half of P3-IDOR-05 `BLOCKED`
because the route answered `404` on that commit. From this change on, they can be run over HTTP. The #149 report
stays a record of its own commit and is not rewritten.

## Verification

- `cd backend && ./gradlew check` on Java 26.0.2 (Temurin), on the commit that adds the resolution table, the audit
  type and this document: passed, 2295 tests, 0 failures, 0 skipped.
