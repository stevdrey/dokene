# Issue 180 verification: explicit AI cancellation spike

Scope: [ADR 0026](../adr/0026-explicit-ai-invocation-cancellation.md), which has status Proposed. All the code is a test-source prototype, and production behaviour is unchanged.

The backend ran on JDK 26 (Temurin 26.0.2) with `openai-java` 4.78.0 and Tomcat 11.0.25. The frontend ran on Vitest 5.0.3.

## Commands

```bash
cd backend && ./gradlew test --tests 'io.github.stevdrey.dokene.spike.cancellation.*' --tests '*CancelledInvocationDurableAuditSpikeIntegrationTest' -i
cd frontend && npx vitest run src/features/followups/__tests__/aiCancellationOrdering.spike.test.ts
```

The [timings](#measured-timings-local-loopback-provider-4s-sdk-timeout) are the `[spike-180]` lines printed by the backend command.

## Acceptance criteria

| Issue requirement | Evidence | Result |
|---|---|---|
| The id is available before the provider call and is bound to tenant, actor and operation; the id alone does not authorize | `AiInvocationCancellationRegistryTest.theIdAloneNeverAuthorizesACancel`, `aDraftCancelRequiresBothPermissionsTheDraftEndpointRequires`, `aRecommendationCancelRequiresFollowUpEvaluate`, `idsAreUniquePerActorAcrossOperationsButDoNotCollideAcrossActors`, `capacityIsReleasedWhenTheOwningRequestEnds`, `capacityHoldsUnderConcurrentRegistration` | Pass |
| Cancellation is idempotent, including the race between cancel and completion | `aCancelThatOvertakesItsRequestIsAppliedWhenTheRequestRegisters`, `aLaterUnderAuthorizedCancelNeverWeakensAnAuthorizedTombstone`, `separatelyInsufficientCancelsNeverCombineIntoAnAuthorizedOne`, `cancelRacingRegistrationIsNeverLost` (5,000 iterations), tombstone scoping, expiry and bound tests, `cancelIsIdempotentAndUnknownIdsAreSilentNoOps`, `cancelAfterCompletionKeepsTheRealOutcome`, `cancelRacingCompletionAlwaysYieldsExactlyOneConsistentOutcome` (5,000 iterations), `cancelRacingTheStartNeverLeavesAStartedCallRunning` (5,000 iterations), `cancelBetweenAttemptsIsCancelledEvenThoughThePreviousAttemptCompleted` | Pass |
| A cancel closes the provider HTTP call in time, and no retry follows | `OpenAiCallCancellationSpikeTest`: the virtual-thread worker closes the socket within about 0–1 ms, both while waiting for headers and while reading the body. One request reaches the provider, the request thread is not interrupted, and the outcome keeps the adapter's `openai` provider id and model. `aWorkerCancelRacingCompletionKeepsTheRealOutcomeAndMetadata` and `aWorkerCancelRacingAFailedCompletionNeverRelabelsIt` each run 5,000 iterations, and a late cancel never relabels an attempt that completed normally or failed for real. `RecommendationCancellationSpikeTest.aCancelDuringBackoffIsStoppedByTheRetryGuardWithoutCountingARetryOrAnAttempt` shows the guard stopping a retry when the cancel arrives **before** the guard runs: 1 attempt recorded, 0 retries, and `CANCELLED` with the metadata of the attempt that ran. A cancel that lands inside the guard, after its check, is not covered; it is listed as an implementation requirement in ADR 0026. The delegating `HttpClient` closes the socket after about 0 ms only before the headers arrive; once the body is being read, it cannot cancel (`theDelegatingHttpClientCannotCancelOnceTheBodyIsBeingRead`). A cancel racing the start of the call never leaves a request running (`aCancelRacingTheStartOfTheHttpCallNeverLeavesARequestRunning`, 5,000 iterations). Negative evidence: a thread interrupt only closes at the 4s SDK timeout, and cancelling the top-level async future never closes the socket | Pass, via the virtual-thread worker |
| The audit row is still written; `CANCELLED` only when the call really was cancelled | `RecommendationCancellationSpikeTest.aWonCancelIsAuditedOnceAsCancelledInTheRequestContextAndIsNotRetried`, `aCancelThatLosesTheRaceKeepsTheGeneratedOutcome`, `aCancelDuringRetryBackoffPreventsTheRetryAndIsAuditedAsCancelled`, `theVirtualThreadWorkerMechanismIsAuditedAsCancelledAndLeavesTheRequestThreadClean` and the real store in `CancelledInvocationDurableAuditSpikeIntegrationTest` | Pass in the real PostgreSQL store, with one durable `FAILED/CANCELLED` row in both cases. With the adapter's interrupt flag left set, the row is written only because the pool had an idle connection, and the flag outlives the request. A contended pool is not verified (see ADR 0026) |
| The frontend sends the cancel before a workspace switch or logout | `aiCancellationOrdering.spike.test.ts` covers nineteen cases. Four are ordering cases, including a fire-and-forget cancel being aborted by the switch. Three exercise a prototype shared tracker: the cancel happens before the switch, the cancel happens before `/logout`, and the cancel gives up at its time bound. Two cover serialized transitions: the latest selection wins when cancels resolve out of order, and a pending switch never restores a tenant after logout. Three cover supersession: of a recommendation and its draft, of a draft, and a supersede in flight during a switch. Four cover provisioning and logout failure: provisioning cancels the old tenant before applying the new one, a later switch wins over pending provisioning, provisioning never restores a tenant after logout, and a failed logout keeps switching usable. Three cover the fifth review: a provisioning superseded while `create()` is pending never cancels the newer tenant, an aborted request that untracks first never drops its pending supersede cancel, and an invocation tracked during the drain is cancelled before the switch applies | Pass. The ordering and the coordinator are shown with a prototype. The real `TenantProvider`/`SessionProvider` are not wired |
| Permits consumed by started calls stay consumed | `permitsConsumedByCancelledCallsStayConsumed`, `aCancelDuringContextAssemblyPreventsTheProviderCallButStillConsumesThePermit` | Pass |

## Measured timings (local loopback provider, 4s SDK timeout)

| Path | Socket closed after a cancel | Requests reaching the provider | Reported outcome |
|---|---|---|---|
| Blocking SDK call + `Thread.interrupt()` | 3,939–3,999 ms (the SDK timeout) | 1 | `CANCELLED`, which is misleading |
| Async client + top-level `future.cancel(true)` | Not closed | 1 | — |
| Delegating `HttpClient` + `handle.cancel()`, before the headers | 0 ms | 1 | `CANCELLED` |
| Delegating `HttpClient` + `handle.cancel()`, while reading the body | Not closed | 1 | `TIMEOUT` (rejected) |
| Virtual-thread worker + `handle.cancel()`, before the headers | 1 ms | 1 | `CANCELLED` |
| Virtual-thread worker + `handle.cancel()`, while reading the body | 0 ms | 1 | `CANCELLED` |

## Not verified here

- A live OpenAI call, and in particular whether OpenAI stops server-side generation and billing when the client closes the connection of a non-streaming call. The spike proves only that the client closes the transport.
- Behaviour behind a TLS-terminating proxy, and a virtual-thread interrupt on a TLS socket (the measurements use plain HTTP on loopback).
- Multiple instances.
- The audit write on an interrupted thread when the Hikari pool is exhausted and the borrow has to wait (the idle-pool case is verified).
- A browser `keepalive` cancel on `pagehide`.
