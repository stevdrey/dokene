# Issue #138 manual dispatch: verification record

Status: both PRs. The first PR delivers the claim, the last-moment gate and synchronous dispatch. The second delivers the bounded recovery processor (ADR 0028 §5) and is recorded under [Recovery processor](#recovery-processor-second-pr).

## Environment

- Date: 2026-10-15. Branch `138-manual-dispatch-intent-and-policy-gate`, based on `40a2359`.
- Temurin Java `26.0.2`, Gradle Wrapper, Testcontainers PostgreSQL 17 (`postgres:17-alpine`) with the `dokene_runtime` and `dokene_migration` roles (`NOBYPASSRLS`).
- No live Meta call: the provider is the scripted `ScriptedMessagingProvider` test bean, and sending is switched on only by the test safety stub. Production stays blocked by the global kill switch (#144).

## Automated coverage

`OutboundDispatchIntegrationTest` (PostgreSQL, real transactions, row locks and advisory locks):

| Acceptance item | Test |
| --- | --- |
| Approved binding sent once; attempt, events, audit and cadence recorded | `sendsTheApprovedBindingOnceAndRecordsAttemptEventsAuditAndCadence` |
| Duplicate click | `aDuplicateClickReplaysTheSameKeyWithoutASecondProviderCall`, `concurrentClicksWithTheSameKeyCreateOneAttemptAndReplayTheOther` |
| Key conflict | `reusingAKeyForAnotherVersionOrMessageIsAConflict` |
| Two concurrent workers or requests | `concurrentClicksWithDifferentKeysReachTheProviderOnce` |
| Kill switch, tenant switch, integration, credential rotation, template edit or unmapping, consent revoked or re-granted: zero provider calls and no writes | `deterministicDenialsMakeNoProviderCallAndWriteNothing` |
| Do-not-contact and archive | `doNotContactAndArchiveBlockTheSend` |
| Stale `If-Match`, missing permission, cross tenant, missing headers | `staleVersionMissingPermissionAndOtherTenantsAreRefusedBeforeAnyEffect` |
| Transient failure and attempt cap | `aTransientFailureReturnsToApprovedAndTheAttemptCapEndsInFailed` |
| Uncertain outcome, never resent | `anUnknownOutcomeIsKeptAndNeverResentAutomaticallyOrManually`, `aProviderThatThrowsIsRecordedAsAnUnknownOutcome` |
| Audit outage before the call: full rollback, no call | `anAuditOutageBeforeTheClaimRollsEverythingBackWithoutAProviderCall` |
| Crash or audit outage after submission: claim kept, sending halted, index backstop | `anAuditOutageAfterTheCallKeepsTheClaimAndHaltsFurtherSends` |
| Consent change during an in-flight call cannot retract it | `aRevocationDuringAnInFlightCallCannotRetractItButBlocksTheNextSend` |
| A membership revoked or demoted after the request started cannot claim, and each refusal is audited as `AUTHORIZATION_DENIED` (Codex review) | `aMembershipRevokedOrDemotedAfterTheRequestStartedCannotClaimTheSend` |
| Dispatch deadline: a slow provider is an unknown outcome, never resent (Codex review, P2) | `aProviderCallThatMissesTheDispatchDeadlineIsAnUnknownOutcome` |
| An interrupted request still records the unknown outcome, then keeps its interrupt (code review) | `anInterruptedRequestStillRecordsTheUnknownOutcomeThenKeepsItsInterrupt` |
| An `Error` in the provider is an immediate unknown outcome (code review) | `anErrorInTheProviderIsAnImmediateUnknownOutcome` |
| The provider runs under the membership as re-read for the claim (code review) | `theProviderRunsUnderTheMembershipAsReReadForTheClaim` |
| A revoked member cannot approve either, and the denial is audited (code review) | `OutboundMessagingGuardIntegrationTest.aMembershipRevokedAfterTheRequestStartedCannotApproveAndTheDenialIsAudited` |

`SendPolicyGateTest` (unit) covers:

- the ADR order, with the kill switch checked before any other read;
- an approval without a fingerprint;
- contact frequency;
- the tenant rate refusal with its remaining window (the caller holds the budget lock);
- a fingerprint mismatch;
- the attempt cap;
- a contact the customer no longer owns;
- that a permitted send carries exactly the bound values, send key and sender.

`MessageApiIntegrationTest.theSendRouteIsDefaultOffAndRefusesBeforeAnyAttempt` covers the HTTP route:
- `403` for a viewer;
- `400` for a missing key, a missing `If-Match` or a weak `If-Match`;
- `409 STALE_VERSION`;
- `404` across tenants;
- `409 OUTBOUND_DISABLED_GLOBALLY` with no attempt row while the kill switch is off;
- `400 INVALID_INPUT` for any request body (`{}`, `{"force":true}`).

`CurrentMembershipAuthorizerTest` (unit) covers the shared re-check: allowed, gone, re-invited, demoted, and no context, each denial audited. `DispatchDeadlineCheckTest` (unit) covers the startup refusal of a Graph timeout that does not fit inside `send-timeout`.

`theTenantSendRateAnswersTooManyRequestsWithRetryAfter` covers `429 RATE_LIMITED` with a `Retry-After` header between 1 and 60 seconds.

## Results

- `./gradlew test --tests '*OutboundDispatchIntegrationTest'`: 19 passed.
- `./gradlew test --tests '*SendPolicyGateTest'`: 9 passed. The rate test also checks the `Retry-After` delay.
- `./gradlew test --tests '*MessageApiIntegrationTest'`: 15 passed.
- `./gradlew check`: passed, 1809 tests, 0 failures, 0 skipped (after the Codex and code review fixes). `TenantPersistenceIntegrationTest` now expects Flyway version 25.

## Recovery processor (second PR)

Branch `138-dispatch-outbox-recovery-processor`, stacked on the first PR. Migration V26 adds `dokene.find_expired_dispatch_claims`.

`DispatchRecoveryIntegrationTest` runs against PostgreSQL with `claim-timeout=PT0S`, so every committed claim is expired at once. Each test makes a halted claim the way production would: an audit outage on `MESSAGE_SENT` after the provider accepted the send.

| Acceptance item | Test |
| --- | --- |
| A crash after submission becomes `OUTCOME_UNKNOWN`; zero extra provider calls; audit attributed to the authorizing member with a correlation id; a later run changes nothing | `aCrashAfterSubmissionBecomesAnUnknownOutcomeWithoutAnotherProviderCall` |
| Two processors racing over one claim record one transition, one event and one audit row | `twoProcessorsRacingOverOneClaimRecordItOnce` |
| A revoked membership, or one demoted to a role without `MESSAGE_SEND`, leaves the claim untouched | `aRevokedOrDemotedAuthorizingMembershipLeavesTheClaimUntouched` |
| An audit outage during recovery keeps the claim; the next run records it | `anAuditOutageDuringRecoveryKeepsTheClaimForTheNextRun` |
| RLS hides attempts from a context-free runtime connection, while the function returns ids only and respects the cutoff, the cursor and the limit; it is `SECURITY DEFINER`, not executable by `PUBLIC`, executable by `dokene_runtime`, with the result shape `(tenant_id, message_id, attempt_id)` | `theExpiredClaimFinderIsTheOnlyCrossTenantReadAndExposesIdsOnly` |
| The processor refuses to start inside a tenant scope | `recoveryRefusesToRunInsideATenantScope` |
| Blocked claims cannot starve the others: with one claim per run, a recoverable claim behind blocked ones is reached (Codex review, P1) | `claimsThatMustBeLeftAloneCannotStarveTheOthers` |
| A membership revoked between the first check and the transaction is caught under the lock and the denial is audited (Codex review, P2) | `aMembershipRevokedAfterTheFirstCheckIsCaughtInsideTheTransaction` |
| An enabled `claim-timeout` below `send-timeout` + 1m fails construction (Codex review, P2; tightened in code review) | `anEnabledClaimTimeoutMustExceedTheDispatchDeadlineByTheMargin` |
| The scheduled run is registered with application-wide scheduling | `theScheduledRunIsRegisteredWhateverTheRecoveryFlag` |
| Only one instance runs recovery at a time: while another session holds the run lock, a run does nothing | `onlyOneInstanceRunsRecoveryAtATime` |

Results:

- `./gradlew test --tests '*DispatchRecoveryIntegrationTest'`: 11 passed.
- `./gradlew check`: passed, 1820 tests, 0 failures, 0 skipped. `TenantPersistenceIntegrationTest` now expects Flyway version 26.

## Not verified here

- Live sending against Meta is out of scope until #144 and #218 land.
- A timed scheduled run itself is not exercised. Tests prove the run is registered, and every Gradle test task sets `dokene.messaging.recovery.enabled=false` and calls `recoverExpiredClaims()` directly.
