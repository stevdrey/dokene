# Issue 180 verification: explicit AI cancellation spike

Scope: [ADR 0026](../adr/0026-explicit-ai-invocation-cancellation.md), which has status Proposed. All the code is a test-source prototype, and production behaviour is unchanged.

The backend ran on JDK 26 (Temurin 26.0.2) with `openai-java` 4.78.0 and Tomcat 11.0.25. The frontend ran on Vitest 5.0.3.

## Commands

```bash
cd backend && ./gradlew test --tests 'io.github.stevdrey.dokene.spike.cancellation.*' -i
cd frontend && npx vitest run src/features/followups/__tests__/aiCancellationOrdering.spike.test.ts
```

The [timings](#measured-timings-local-loopback-provider-4s-sdk-timeout) are the `[spike-180]` lines printed by the backend command.

## Acceptance criteria

| Issue requirement | Evidence | Result |
|---|---|---|
| The id is available before the provider call and is bound to tenant, actor and operation; the id alone does not authorize | `AiInvocationCancellationRegistryTest.theIdAloneNeverAuthorizesACancel`, `idsAreUniquePerActorAcrossOperationsButDoNotCollideAcrossActors`, `capacityIsReleasedWhenTheOwningRequestEnds`, `capacityHoldsUnderConcurrentRegistration` | Pass |
| Cancellation is idempotent, including the race between cancel and completion | `cancelIsIdempotentAndUnknownIdsAreSilentNoOps`, `cancelAfterCompletionKeepsTheRealOutcome`, `cancelRacingCompletionAlwaysYieldsExactlyOneConsistentOutcome` (5,000 iterations), `cancelRacingTheStartNeverLeavesAStartedCallRunning` (5,000 iterations), `cancelBetweenAttemptsIsCancelledEvenThoughThePreviousAttemptCompleted` | Pass |
| A cancel closes the provider HTTP call in time, and no retry follows | `OpenAiCallCancellationSpikeTest`: the delegating `HttpClient` closes the socket after about 0 ms, and only one request reaches the provider. A cancel racing the start of the call never leaves a request running (`aCancelRacingTheStartOfTheHttpCallNeverLeavesARequestRunning`, 5,000 iterations). Negative evidence: a thread interrupt only closes at the 4s SDK timeout, and cancelling the top-level async future never closes the socket | Pass, via the delegating `HttpClient` only |
| The audit row is still written; `CANCELLED` only when the call really was cancelled | `RecommendationCancellationSpikeTest.aWonCancelIsAuditedOnceAsCancelledInTheRequestContextAndIsNotRetried`, `aCancelThatLosesTheRaceKeepsTheGeneratedOutcome`, `aCancelDuringRetryBackoffPreventsTheRetryAndIsAuditedAsCancelled` | Pass, with a recording audit port. The real JDBC recorder is not exercised (see the interrupt-flag risk in ADR 0026) |
| The frontend sends the cancel before a workspace switch or logout | `aiCancellationOrdering.spike.test.ts` covers seven cases. Four are ordering cases, including a fire-and-forget cancel being aborted by the switch. Three exercise a prototype shared tracker: the cancel happens before the switch, the cancel happens before `/logout`, and the cancel gives up at its time bound | Pass. The ordering and the coordinator are shown with a prototype. The real `TenantProvider`/`SessionProvider` are not wired |
| Permits consumed by started calls stay consumed | `permitsConsumedByCancelledCallsStayConsumed`, `aCancelDuringContextAssemblyPreventsTheProviderCallButStillConsumesThePermit` | Pass |

## Measured timings (local loopback provider, 4s SDK timeout)

| Path | Socket closed after a cancel | Requests reaching the provider | Reported outcome |
|---|---|---|---|
| Blocking SDK call + `Thread.interrupt()` | 3,939–3,999 ms (the SDK timeout) | 1 | `CANCELLED`, which is misleading |
| Async client + top-level `future.cancel(true)` | Not closed | 1 | — |
| Delegating `HttpClient` + `handle.cancel()` | 0 ms | 1 | `CANCELLED` |

## Not verified here

- A live OpenAI call.
- Behaviour behind a TLS-terminating proxy.
- Multiple instances.
- The real Hikari/JDBC audit write on a thread that is still interrupted.
- A browser `keepalive` cancel on `pagehide`.
