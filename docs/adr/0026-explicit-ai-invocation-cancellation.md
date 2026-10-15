# ADR 0026: Explicit Client-Driven Cancellation of In-Flight AI Invocations

## Status

Proposed. This is the outcome of the spike for [Issue #180](https://github.com/stevdrey/dokene/issues/180), which follows up on [Issue #178](https://github.com/stevdrey/dokene/issues/178) and the limitation recorded in [ADR 0019](0019-ai-failure-handling-telemetry-and-audit.md) §7.

Nothing in this ADR is implemented in production code. The prototype lives in test sources (`backend/src/test/java/io/github/stevdrey/dokene/spike/cancellation/`, plus one frontend spike test), and the evidence is in [the verification record](../verification/issue-180-ai-cancellation-spike-verification.md).

Accepting this ADR requires two things: the measured waste must justify the extra contract (see "Recommendation"), and the user must approve the implementation.

## Context

ADR 0019 §7 explains why a browser abort never reaches the backend. Tomcat does not report the disconnect of a pending request, so an abandoned recommendation or draft call that has reached OpenAI runs to completion. That call is paid for, counts against the rate limits, and is audited with its real outcome.

The recommendation and draft endpoints are synchronous. The provider call blocks the request thread inside `OpenAiResponsesApiAdapter`, which uses the blocking `client.responses().create(...)`. The adapter already maps an interrupt or a `CancellationException` to `CANCELLED`. Nothing ever cancels the call from another thread.

The spike had to show six things before any implementation could be approved:

1. A cancellation identifier exists before the provider call starts, and it is bound to tenant, actor and operation. The identifier alone must not authorize a cancel.
2. Cancellation is idempotent, including in the race between cancel and completion.
3. Cancelling actually closes the provider HTTP call in time, and no retry runs afterwards.
4. The durable audit row is still written: `CANCELLED` only when the call really was cancelled, and `GENERATED` otherwise.
5. The frontend sends the cancel before switching workspace or logging out, while the authorized context still exists.
6. Rate-limit permits consumed by started calls stay consumed.

## Findings

| # | Question | Result | Evidence |
|---|---|---|---|
| 3 | Does interrupting the blocking SDK call close the socket? | **No.** A platform thread blocked in a socket read ignores the interrupt. The provider socket closed only at the SDK timeout: about 4.0s with a 4s timeout, against a target of under 1s. The adapter still reports `CANCELLED`, which is misleading because the provider kept working until the timeout. | `OpenAiCallCancellationSpikeTest.interruptingTheBlockingSdkCallDoesNotCloseTheProviderSocketPromptly` |
| 3 | Does cancelling the future of the async client (`client.async().responses().create(...)`) close it? | **No.** The SDK derives the returned future from the HTTP future with dependent stages, and a cancel does not propagate back up that chain. `okhttp3.Call#cancel` never runs. | `...cancellingTheTopLevelAsyncSdkFutureDoesNotReachTheHttpCall` |
| 3 | Does cancelling the inner HTTP future close it? | **Yes, in about 0 ms.** In `openai-java` 4.78.0, `OkHttpClient.executeAsync` cancels the `okhttp3.Call` when its own future is cancelled (checked with `javap`). A delegating `com.openai.core.http.HttpClient` keeps the blocking adapter as it is, runs the exchange through `executeAsync`, and attaches that inner future to a per-invocation handle bound with a `ScopedValue`. The adapter maps the outcome to `CANCELLED`. Exactly one request reached the provider, so nothing retried. | `...cancellingThroughTheDelegatingHttpClientClosesTheSocketAndMapsToCancelled` |
| 3 | Cancel before the call starts | No connection is opened, and the result is `CANCELLED`. Checking for a cancel and starting the call happen under the same lock that `cancel()` takes, so there is no window in between. Either the start sees the cancel and sends nothing, or the cancel sees the started call and aborts it. Across 5,000 iterations of cancel vs. start, a started request was never left running. | `...cancelBeforeTheProviderCallStartsSendsNothing`, `...aCancelRacingTheStartOfTheHttpCallNeverLeavesARequestRunning` |
| 3, 4 | Cancel during retry backoff | Every attempt goes through the handle. A cancel that arrives after a failed attempt completed prevents the retry; no second request is sent. The handle records that prevention, so the outcome is `CANCELLED` even though the previous attempt's future had completed normally. | `RecommendationCancellationSpikeTest.aCancelDuringRetryBackoffPreventsTheRetryAndIsAuditedAsCancelled`, `AiInvocationCancellationRegistryTest.cancelBetweenAttempts...` |
| 1, 2 | Binding, idempotency and the race | The registry key is (tenant, actor, client id). The tenant and actor come from the trusted server context, and an id is unique for an actor across all operations, so the cancel route is unambiguous. The operation is stored with the entry and checked against the caller's current permissions. A cancel from another tenant or actor, or without permission for the stored operation, is a no-op. Capacity is reserved atomically with a semaphore and never exceeded under concurrent registration. A repeated cancel, a cancel for an unknown id, and a cancel after completion are all silent no-ops. The race is settled by the atomic completion of the HTTP future: across 5,000 iterations each of cancel vs. completion and cancel vs. call start, there was always exactly one consistent outcome. | `AiInvocationCancellationRegistryTest` |
| 4 | Audit | When the cancel wins, `FAILED/CANCELLED` is audited exactly once, on the request thread, inside the request's real `ScopedValue` tenant context, and the provider is invoked once. When the cancel loses, the outcome stays `GENERATED`. No migration is needed, because `CANCELLED` is already an allowed `FAILED` detail (V13). | `RecommendationCancellationSpikeTest` |
| 4 | Interrupt flag (**risk**) | Today the adapter re-asserts the thread's interrupt flag when it maps a `CancellationException`. On a pooled Tomcat thread that flag would still be set during the blocking audit write that follows. A connection-pool borrow or a JDBC call can fail when the thread is interrupted, which would turn a cancel into a fail-closed 503. The spike asserts that the flag is left set but did not exercise Hikari. | `...cancellingThroughTheDelegatingHttpClient...` (asserts the flag) |
| 6 | Rate limit | Permits are taken before the provider call and are never refunded. After N cancelled calls, call N+1 is throttled. A cancel that lands during context assembly prevents the provider call but still consumes the permit, which is the conservative choice. | `RecommendationCancellationSpikeTest.permitsConsumed...`, `...aCancelDuringContextAssembly...` |
| 5 | Frontend ordering | A cancel sent through `httpClient` before `apiClient.setCurrentTenantId(next)` carries the original tenant and CSRF token. Sent afterwards, it carries the new tenant and would silently miss. Sent after logout, it has neither tenant nor CSRF token. A fire-and-forget cancel is itself aborted by `setCurrentTenantId`, because `cancelTenantRequests` aborts it. A prototype tracker that acts as the shared coordinator (§7) sends only the old tenant's cancels, finishes them before the switch and before `/logout`, and gives up at its time bound. | `frontend/src/features/followups/__tests__/aiCancellationOrdering.spike.test.ts` |

## Proposed design (if approved)

1. **Identifier.** The client generates a UUID (`crypto.randomUUID()`) and sends it as `X-Ai-Invocation-Id` on `POST /customers/{id}/recommendation` and `/draft`. The single-request contract is unchanged; the header is optional at first, and a malformed value is a 400.
2. **Registry.** An application-layer, in-memory `AiInvocationCancellationRegistry` keyed by (tenant, actor, id). The operation is stored in the entry.
   - An entry lives exactly as long as the request that owns it: it is registered before context assembly and removed in `finally`. A lost cancel therefore leaves nothing behind, and there is no TTL that could expire a live entry.
   - Capacity is bounded by a semaphore that is reserved before insertion and released only when the owning request removes its entry. A full registry rejects the request with 429 before any provider work.
   - A duplicate id for the same actor is rejected, whatever the operation. Ids from different actors never collide.
3. **Cancel endpoint.** `POST /api/ai-invocations/{id}/cancel`.
   - It is tenant-scoped (`X-Tenant-Id`) and requires CSRF. It acts only if the caller currently holds the permission for the stored operation: `FOLLOWUP_EVALUATE` for a recommendation, `MESSAGE_DRAFT` for a draft. Otherwise it is a no-op.
   - It always returns 204, so there is no existence oracle. It is idempotent.
   - It does not write an audit row; the invocation's own terminal outcome is the record.
4. **Provider.** `OpenAiConfiguration` builds `OpenAIClientImpl` from `ClientOptions`, using the SDK OkHttp client wrapped in a delegating `HttpClient` (the spike's `CancellableHttpClient`). The adapter keeps its blocking shape, and `maxRetries(0)` is unchanged.
5. **Outcome.** `CANCELLED` is reported only when the handle actually aborted provider work. That means one of two things: an attempt, whether the first or a retry, was prevented from starting, or the current attempt's HTTP future was cancelled before it completed. Otherwise the real outcome is kept, as in ADR 0019 §7. The interrupt flag must not leak into the reporting path: the adapter must not re-interrupt for an explicit cancel, or the service must clear the flag before reporting. An integration test with the real audit recorder must cover this.
6. **Retry and limits.** `CANCELLED` is already non-retryable. Every attempt starts through the handle, so a retry is never sent after a cancel. Permits are never refunded.
7. **Frontend.**
   - **Ownership boundary.** The in-flight invocation belongs to the `useFollowUpAssistant` hook, which is a descendant of `TenantProvider` and `SessionProvider`, so those providers cannot read it. The shared coordinator is a non-React singleton, `aiInvocationTracker`, that lives next to `apiClient`:
     - `track({tenantId, invocationId, operation})` returns an `untrack` function. The hook tracks when it sends a request and untracks when the request settles or is aborted.
     - `cancelTenant(tenantId, timeoutMs)` sends the cancels for that tenant and awaits them. It is bounded (for example 1s) and ignores errors, so it never blocks the user.
   - **Wiring.** `switchWorkspace` becomes asynchronous and awaits `aiInvocationTracker.cancelTenant(current)` before `apiClient.setCurrentTenantId(next)`. `logout` awaits the same call before `POST /logout`. The `handleUnauthorized` path has no authorized context left, so it cannot cancel.
   - **What is proven so far.** The spike proves this ordering with a prototype tracker driven through the real `apiClient` and `httpClient`. Wiring the real providers is a production change, so it belongs to the implementation.
   - When the user leaves a customer, the hook sends the cancel before aborting locally.
   - On `pagehide`, the client sends a best-effort `fetch(..., { keepalive: true, credentials: 'include' })` with the tenant and CSRF headers. If that is lost, the provider call simply runs to completion, as it does today.

## Limitations

- **Per instance.** The registry is in memory, like the rate limiter (ADR 0019 §7). A cancel that lands on another replica is a silent no-op. Supporting multiple instances would need sticky sessions or a shared registry, and a shared registry cannot hold the OkHttp call, so cancellation would have to become a notification to the owning node. This is deferred along with the other multi-instance work.
- **A cancel can be lost**, for example when a tab is closed abruptly. The call then completes as it does today, and the owning request removes its entry when it ends.
- **A won cancel is not free.** The provider may already have done some of the work, and the permit stays consumed. The saving is the part of the generation that has not happened yet and the output tokens not yet produced.
- **No monetary or token budget.** That concern is still separate, as in ADR 0019 §7.

## Alternatives considered

The alternatives from #178 still stand:

- streaming with heartbeats, which needs a new response contract;
- a different servlet container, whose behaviour is unverified;
- OpenAI `background=true` with cancel-by-id, which needs polling and temporary storage of the result.

The spike also rejected two simpler variants, because neither closes the socket in time:

- thread interrupts;
- cancelling the top-level async future.

## Recommendation

The approach is feasible and safe as designed above. However, ADR 0019 §7 is low severity and **no waste measurement exists yet**. Before implementing:

1. Count the abandoned invocations: `GENERATED` outcomes whose result the frontend discarded. A cheap proxy is the existing local abort counted client-side, or the provider latency of calls that were not followed by a draft or an action.
2. Implement only if abandoned calls are a material share of provider cost.

Until then, ADR 0019 §7 stays the accepted behaviour.

## Consequences (if accepted)

- One new authorized endpoint, one optional request header, and an asynchronous `switchWorkspace` and logout ordering in the frontend.
- The provider adapter depends on SDK internals: `ClientOptions`, the `HttpClient` interface, and the fact that `executeAsync` cancels the OkHttp call. An SDK upgrade must rerun `OpenAiCallCancellationSpikeTest` or its production successor.
- The audit vocabulary and the database constraints are unchanged.
