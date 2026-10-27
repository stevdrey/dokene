# Issue #140 — T12 message resolution route

## Change

`POST /api/messages/{messageId}/resolutions` is now served, as frozen by ADR 0023 §2 (T12), §3.1 and the §6 role
matrix. It is API only: no resolution UI is part of this change (Phase 3 scope item 10 still needs a design).

- `MessageController.resolve`: `@RequiredPermission(INTEGRATION_MANAGE)`, so OPERATOR and VIEWER are denied, and
  audited, before the headers or body are read (issue 162). The body is read strictly as
  `ResolutionRequest { outcome: FAILED, note? }`. Any other `outcome`, an unknown member or a note over 500 characters
  is `400 INVALID_INPUT`. The response is `201` on the first request and `200` on a replay. The body is the message
  representation, with the next version as its `ETag`. Errors are `application/problem+json`.
- `OutboundMessageCommandService.resolve`: checks the permission again, then validates input, `Idempotency-Key` and a
  strict `If-Match`. It takes the idempotency lock, then the customer lock, then the message lock. It checks the
  version, then re-reads the membership under the customer lock and requires `INTEGRATION_MANAGE`. It then calls
  `OutboundMessage.resolveFailed`: `SENDING` with an unknown outcome becomes `FAILED`/`UNKNOWN` and records a member
  `SEND_FAILED` event. Anything else is `409 INVALID_TRANSITION`. The command writes the `MESSAGE_SEND_FAILED` audit
  row and records the idempotency key (operation `RESOLUTION`, `record_id` = the event id, fingerprint
  `messageId, ifMatch, outcome, note` per spec 0001). It never calls the provider.
- No migration: `RESOLUTION` was already allowed by V18, and the domain transition and contract types were already on
  `main`.
- The note is part of the request fingerprint only. Spec 0001 defines no resolution record, so it is not stored or
  returned.

## Security cases

| Case | Test (`MessageResolutionIntegrationTest`, HTTP against PostgreSQL) |
| --- | --- |
| P3-PERM-07 allow (OWNER, ADMIN) | `anOwnerResolvesAnUnknownOutcomeAsFailedAndAReplayReturnsTheSameResult`, `anAdminMayResolve` |
| P3-PERM-07 deny (OPERATOR, VIEWER), one `AUTHORIZATION_DENIED` per request, also for malformed input | `operatorsAndViewersAreDeniedAndTheDenialIsAudited` |
| P3-APPR-12 (a delivery report makes the held ETag stale) | `anETagMadeStaleByADeliveryReportIsRefused` |
| P3-DUP-08 (`SENT` refused with `400`, `FAILED` applied, replay `200` with the same result, key reuse `409`) | `anOwnerResolvesAnUnknownOutcomeAsFailedAndAReplayReturnsTheSameResult` |
| P3-IDOR-05, resolution half (tenant B's admin gets `404`, the message is unchanged) | `anotherTenantsMessageIsNotFound` |
| Message whose outcome is known: `409 INVALID_TRANSITION`; stale, weak or missing `If-Match`, missing key, strict body | `aMessageWhoseOutcomeIsKnownCannotBeResolvedAndIfMatchIsStrict` |
| No provider call, `MESSAGE_SEND_FAILED` audit row attributed to the resolving member | asserted in every test above (`provider.calls()`, `audit_events`) |

`RequiredPermissionCoverageTest` and `MessagingContractFixturesTest` (the frozen `contracts/messaging/v1` fixtures)
stay green. The route's contract was already frozen there.

## Effect on the #149 run

The #149 candidate run marked P3-PERM-07, P3-DUP-08, P3-APPR-12 and the resolution half of P3-IDOR-05 `BLOCKED`
because the route answered `404` on that commit. From this change on, they can be run over HTTP. The #149 report
stays a record of its own commit and is not rewritten.

## Verification

- `cd backend && ./gradlew check` (Java 26 toolchain): see the PR for the result on the final commit.
