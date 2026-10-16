# Issue #138 manual dispatch: verification record

Status: first PR of two (claim, last-moment gate, synchronous dispatch). The bounded recovery processor (ADR 0028 §5) is verified in the second PR.

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

`SendPolicyGateTest` (unit) covers:

- the ADR order, with the kill switch checked before any other read;
- an approval without a fingerprint;
- contact frequency;
- the tenant rate counted under the budget lock;
- a fingerprint mismatch;
- the attempt cap;
- a contact the customer no longer owns;
- that a permitted send carries exactly the bound values, send key and sender.

`MessageApiIntegrationTest.theSendRouteIsDefaultOffAndRefusesBeforeAnyAttempt` covers the HTTP route: `403` for a viewer, `400` for a missing key, a missing `If-Match` or a weak `If-Match`, `409 STALE_VERSION`, `404` across tenants, and `409 OUTBOUND_DISABLED_GLOBALLY` with no attempt row while the kill switch is off.

## Results

- `./gradlew test --tests '*OutboundDispatchIntegrationTest'`: 14 passed.
- `./gradlew test --tests '*SendPolicyGateTest'`: 8 passed.
- `./gradlew test --tests '*MessageApiIntegrationTest'`: 14 passed.
- `./gradlew check`: passed, 1794 tests, 0 failures, 0 skipped. `TenantPersistenceIntegrationTest` now expects Flyway version 25.

## Not verified here

- The crash-recovery path that turns an expired `STARTED` claim into an unknown outcome is in PR 2.
- Live sending against Meta is out of scope until #144 and #218 land.
