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
| 3 | Does interrupting the blocking SDK call close the socket? | **No.** A platform thread blocked in a socket read ignores the interrupt. OkHttp honours an interrupt only at its own checkpoints, for example while it is still writing the request, so the outcome depends on timing and covers none of the waiting. The provider socket closed only at the SDK timeout: about 4.0s with a 4s timeout, against a target of under 1s. The adapter still reports `CANCELLED`, which is misleading because the provider kept working until the timeout. | `OpenAiCallCancellationSpikeTest.interruptingTheBlockingSdkCallDoesNotCloseTheProviderSocketPromptly` |
| 3 | Does cancelling the future of the async client (`client.async().responses().create(...)`) close it? | **No.** The SDK derives the returned future from the HTTP future with dependent stages, and a cancel does not propagate back up that chain. `okhttp3.Call#cancel` never runs. | `...cancellingTheTopLevelAsyncSdkFutureDoesNotReachTheHttpCall` |
| 3 | Does cancelling the inner HTTP future close it? | **Only until the headers arrive; rejected.** Before the headers it closes in about 0 ms. In `openai-java` 4.78.0, `OkHttpClient.executeAsync` cancels the `okhttp3.Call` when its own future is cancelled (checked with `javap`). A delegating `com.openai.core.http.HttpClient` keeps the blocking adapter as it is, runs the exchange through `executeAsync`, and attaches that inner future to a per-invocation handle bound with a `ScopedValue`. The adapter maps the outcome to `CANCELLED`. Exactly one request reached the provider, so nothing retried. **However**, that future completes as soon as the headers arrive (`onResponse`), while the SDK reads and parses the body afterwards. A cancel during the body read cannot reach `Call#cancel`: against a provider that sends headers and part of a body and then stalls, the socket stayed open and the call ended in `TIMEOUT`. | `...cancellingThroughTheDelegatingHttpClientClosesTheSocketAndMapsToCancelled`, `...theDelegatingHttpClientCannotCancelOnceTheBodyIsBeingRead` |
| 3 | Does a virtual-thread worker close it in every phase? | **Yes. This is the recommended mechanism.** Each provider attempt runs on its own virtual thread, started through the handle, and a cancel interrupts that worker. On a virtual thread, an interrupt during blocking `java.net.Socket` I/O closes the socket. The socket closed within about 0–1 ms both while waiting for headers and while reading the body, the outcome was `CANCELLED`, and exactly one request was sent. The request thread is **never interrupted**, which removes the interrupt-flag risk below for this mechanism. The approach uses no SDK internals. | `...aVirtualThreadWorkerClosesTheSocketWhileWaitingForHeaders`, `...WhileReadingTheBody`, `RecommendationCancellationSpikeTest.theVirtualThreadWorkerMechanism...` |
| 3, 4 | Outcome and metadata of a cancelled worker | Cancelling must not replace the attempt's own result with a bare `CancellationException`, because that loses the provider and model metadata that telemetry tags depend on (ADR 0019). The worker therefore keeps the attempt's real result, and the request thread waits for it.<br><br>This exposed an **adapter gap**: when the socket closes during the body read, the failure surfaces inside the SDK's JSON handling as an `OpenAIException`. The adapter maps that to `UNAVAILABLE`, which is retryable, because that branch never checks for interruption. The decorator normalizes the result: once a cancel was requested before the attempt finished, any failure becomes `CANCELLED`, keeping the adapter's `openai` provider id and model. An attempt prevented before it starts reports `none` as its provider, which is ADR 0019's "no provider reached". A late cancel never relabels an attempt that completed normally: across 5,000 cancel-vs-completion races the outcome was always consistent. | `...aVirtualThreadWorkerClosesTheSocket...` (asserts `openai` and the model), `...aWorkerCancelRacingCompletionKeepsTheRealOutcomeAndMetadata` |
| 3 | Cancel before the call starts | No connection is opened, and the result is `CANCELLED`. Checking for a cancel and starting the call happen under the same lock that `cancel()` takes, so there is no window in between. Either the start sees the cancel and sends nothing, or the cancel sees the started call and aborts it. Across 5,000 iterations of cancel vs. start, a started request was never left running. | `...cancelBeforeTheProviderCallStartsSendsNothing`, `...aCancelRacingTheStartOfTheHttpCallNeverLeavesARequestRunning` |
| 3, 4 | Cancel during retry backoff | Every attempt goes through the handle. A cancel that arrives after a failed attempt completed prevents the retry; no second request is sent. The handle records that prevention, so the outcome is `CANCELLED` even though the previous attempt's future had completed normally. | `RecommendationCancellationSpikeTest.aCancelDuringRetryBackoffPreventsTheRetryAndIsAuditedAsCancelled`, `AiInvocationCancellationRegistryTest.cancelBetweenAttempts...` |
| 1, 2 | A cancel that overtakes its request | The client knows the id before it sends the request, so the cancel can reach the server first. A cancel that finds no entry leaves a short-lived tombstone recording the caller's permissions. A later `register` with the same key consumes it, and that registration is born cancelled: the provider is never called. The tombstone applies only if it has the same tenant and actor and the permissions cover the operation. Tombstones expire after a TTL and their store is bounded; when it is full the cancel is dropped, which is today's behaviour. `register` and `cancel` share one lock, so across 5,000 cancel-vs-register races the outcome was always cancelled. | `AiInvocationCancellationRegistryTest.aCancelThatOvertakesItsRequest...`, `...aTombstoneOnlyApplies...`, `...anExpiredTombstone...`, `...theTombstoneStoreIsBounded...`, `...cancelRacingRegistrationIsNeverLost` |
| 1, 2 | Binding, idempotency and the race | The registry key is (tenant, actor, client id). The tenant and actor come from the trusted server context, and an id is unique for an actor across all operations, so the cancel route is unambiguous. The operation is stored with the entry and checked against the caller's current permissions. A cancel from another tenant or actor, or without permission for the stored operation, is a no-op. Capacity is checked under the registry lock and is never exceeded under concurrent registration. A repeated cancel, a cancel for an unknown id, and a cancel after completion are all silent no-ops. The race is settled by the atomic completion of the HTTP future: across 5,000 iterations each of cancel vs. completion and cancel vs. call start, there was always exactly one consistent outcome. | `AiInvocationCancellationRegistryTest` |
| 4 | Audit | When the cancel wins, `FAILED/CANCELLED` is audited exactly once, on the request thread, inside the request's real `ScopedValue` tenant context, and the provider is invoked once. When the cancel loses, the outcome stays `GENERATED`. No migration is needed, because `CANCELLED` is already an allowed `FAILED` detail (V13). | `RecommendationCancellationSpikeTest` |
| 4 | Durable audit and the interrupt flag (**risk for the delegating-client design**) | This was checked against the real store: PostgreSQL 17, Hikari, and `append_audit_event`.<br><br>**Flag cleared.** A won cancel writes exactly one durable `FAILED/CANCELLED` row for the customer.<br><br>**Flag left set**, which is what the adapter does today for a `CancellationException`: the row is still written, but only because the pool had an idle connection. The flag then survives the request, so a pooled Tomcat worker would carry it into its next request.<br><br>Not exercised: a borrow that has to wait for a connection. Hikari's wait is interruptible, so the audit write could fail in that case and turn the cancel into a fail-closed 503. | `CancelledInvocationDurableAuditSpikeIntegrationTest` (case A: flag left set; case B: flag cleared), `...cancellingThroughTheDelegatingHttpClient...` (asserts the adapter leaves the flag set) |
| 6 | Rate limit | Permits are taken before the provider call and are never refunded. After N cancelled calls, call N+1 is throttled. A cancel that lands during context assembly prevents the provider call but still consumes the permit, which is the conservative choice. | `RecommendationCancellationSpikeTest.permitsConsumed...`, `...aCancelDuringContextAssembly...` |
| 5 | Frontend ordering | A cancel sent through `httpClient` before `apiClient.setCurrentTenantId(next)` carries the original tenant and CSRF token. Sent afterwards, it carries the new tenant and would silently miss. Sent after logout, it has neither tenant nor CSRF token. A fire-and-forget cancel is itself aborted by `setCurrentTenantId`, because `cancelTenantRequests` aborts it. A prototype tracker that acts as the shared coordinator (§7) sends only the old tenant's cancels, finishes them before the switch and before `/logout`, and gives up at its time bound. | `frontend/src/features/followups/__tests__/aiCancellationOrdering.spike.test.ts` |

## Proposed design (if approved)

1. **Identifier.** The client generates a UUID (`crypto.randomUUID()`) and sends it as `X-Ai-Invocation-Id` on `POST /customers/{id}/recommendation` and `/draft`. The single-request contract is unchanged; the header is optional at first, and a malformed value is a 400.
2. **Registry.** An application-layer, in-memory `AiInvocationCancellationRegistry` keyed by (tenant, actor, id). The operation is stored in the entry.
   - An entry lives exactly as long as the request that owns it: it is registered before context assembly and removed in `finally`. A lost cancel therefore leaves nothing behind, and there is no TTL that could expire a live entry.
   - Capacity is checked under the registry lock and freed only when the owning request removes its entry. A full registry rejects the request with 429 before any provider work.
   - **Tombstones.** A cancel that finds no entry leaves a tombstone keyed by (tenant, actor, id) with the caller's permissions. The tombstone lives for the provider's maximum timeout plus a margin. A repeated cancel adds its own permission snapshot to the tombstone and never replaces an earlier one. The tombstone applies only if a **single** snapshot covers the operation. As a result, a later, weaker cancel cannot downgrade an earlier authorized one, and two cancels that are each insufficient never combine into an authorized one. The tombstone store has a global cap, and the implementation should add a per-actor cap. A later registration with the same key consumes the tombstone, and that registration starts cancelled if the permissions cover its operation. A full store drops the cancel. Ids are fresh UUIDs, so a tombstone left by a cancel that arrives after its request finished is harmless and expires.
   - A duplicate id for the same actor is rejected, whatever the operation. Ids from different actors never collide.
3. **Cancel endpoint.** `POST /api/ai-invocations/{id}/cancel`.
   - It is tenant-scoped (`X-Tenant-Id`) and requires CSRF.
   - It is declared with `@RequiredPermission(value = {FOLLOWUP_EVALUATE, MESSAGE_DRAFT}, anyOf = true)`, so it stays compatible with `RequiredPermissionInterceptor` and `RequiredPermissionCoverageTest`. A caller holding neither permission gets the interceptor's audited 403. Such a caller cannot have started any AI invocation, and the 403 depends only on the caller's own permissions, never on the id, so it is not an existence oracle.
   - The registry then checks the stored operation's exact conjunction: `FOLLOWUP_EVALUATE` for a recommendation, and both `MESSAGE_DRAFT` and `FOLLOWUP_EVALUATE` for a draft. A mismatch is a silent no-op that still returns 204.
   - It always returns 204, so there is no existence oracle. It is idempotent.
   - It does not write an audit row; the invocation's own terminal outcome is the record.
4. **Provider.** A decorator (the spike's `VirtualThreadCancellableProvider`) sits between `ResilientAiProvider` and the unchanged OpenAI adapter.
   - It runs every attempt on a virtual thread, started through `handle.start(...)`, and the request thread waits for it. A cancel interrupts the worker, which closes the socket in any phase of the HTTP exchange.
   - The request thread waits for the attempt's **own** terminal result, so the provider and model metadata survive.
   - A cancel and the worker publishing its outcome are decided by one atomic state transition. If the cancel wins and the attempt then fails, the failure is reported as `CANCELLED` with that metadata. If the outcome was published first, it is never relabelled.
   - The production adapter should also check for interruption in every catch branch, so that an interrupted body read is never classified as a retryable `UNAVAILABLE`. An attempt that completes normally despite a late cancel keeps its real outcome.
   - The client configuration is unchanged, including `maxRetries(0)`, and nothing depends on SDK internals.
   - The worker does not inherit `ScopedValue` bindings. The correlation id that `AuditAiCorrelationSource` reads must be captured on the request thread and rebound in the worker. Tenant context is not needed there, because reporting stays on the request thread.
   - Virtual threads are not bounded by the Tomcat pool. Concurrency stays bounded by the registry capacity and the rate limits.
   - The delegating `HttpClient` (`CancellableHttpClient`) is **rejected**, because it cannot cancel once the body is being read.
5. **Outcome.** `CANCELLED` is reported only when the handle actually aborted provider work. That means one of two things: an attempt, whether the first or a retry, was prevented from starting, or the current attempt's worker was cancelled before it completed. Otherwise the real outcome is kept, as in ADR 0019 §7. The interrupt flag must not leak into the reporting path or past the request. With the virtual-thread worker, only the worker is interrupted, and the spike asserts that the request thread stays clean. The production integration test must still assert it, and must cover a pool under contention as well as the idle pool.
6. **Retry and limits.** `CANCELLED` is already non-retryable. A cancel that arrives during backoff is checked in the `RetryGuard` that `FollowUpRecommendationService.invocationProvider` already builds. That guard runs before `retryScheduled` and before any attempt is recorded. It throws `CANCELLED`, carrying the metadata of the attempt that really ran. `handle.start` stays as the last line of defence.
   - The guard check alone only **reduces** accounting for retries that never happen. A cancel that lands just after the check still lets the retry be counted, and the attempt that `handle.start` then prevents is recorded too. Fully avoiding phantom accounting needs the atomic start described in "Requirements carried to the implementation". Permits are never refunded.
7. **Frontend.**
   - **Ownership boundary.** The in-flight invocation belongs to the `useFollowUpAssistant` hook, which is a descendant of `TenantProvider` and `SessionProvider`, so those providers cannot read it. The shared coordinator is a non-React singleton, `aiInvocationTracker`, that lives next to `apiClient`:
     - `track({tenantId, invocationId, operation})` returns an `untrack` function. The hook tracks when it sends a request and untracks when the request settles.
     - `supersede(invocationId)` is the only path for a local supersession. Today those paths abort directly: a new recommendation aborts the previous recommendation and draft (`useFollowUpAssistant.ts` l.154-155), and a new draft aborts the previous draft (l.196); leaving a customer and unmounting abort too. `supersede` sends the server cancel first and then the hook aborts locally. The entry stays tracked until that cancel settles, even if the aborted request untracks first: the request and the pending cancel each own the entry, and it is removed only when both are done. A switch or logout in the meantime sends the cancel again (cancel is idempotent).
     - While a transition awaits `cancelTenant`, that tenant is **draining**. An invocation tracked during the drain is cancelled immediately, and the drain waits until no new cancel appears. It is still bounded by the timeout.
     - `cancelTenant(tenantId, timeoutMs)` sends the cancels for that tenant and awaits them. It is bounded (for example 1s) and ignores errors, so it never blocks the user.
   - **Wiring.** Every place that changes the tenant has to be classified.
     - **User-initiated transitions go through the coordinator.** `switchWorkspace` becomes asynchronous and awaits `aiInvocationTracker.cancelTenant(current)` before `apiClient.setCurrentTenantId(next)`. `provisionWorkspace` (`TenantContext.tsx`, which today calls `setCurrentTenantId` directly) creates the workspace first. If no later transition has started meanwhile, it then follows the same cancel-then-apply path. A superseded provisioning stops before cancelling anything, so it can never cancel another tenant's invocations. `logout` awaits the same cancel before `POST /logout`.
     - **Involuntary transitions cannot cancel**, because the backend has already withdrawn authorization or ended the session, and the provider call ends as it does today. These are: a `refreshWorkspaces` that finds the active membership gone, `handleUnauthorized` after a 401, and the session-status effect when the session turns unauthenticated.
   - **Serialized transitions.** A generation counter serializes these transitions. Every switch, every provisioning and every logout increments it. After its awaited cancels, a tenant change is applied only if no later transition has started, so the latest selection wins even when the cancels resolve out of order. Logout supersedes any pending transition immediately. It becomes terminal only after `POST /logout` succeeds, and from then on nothing restores a tenant. A failed logout leaves the session and workspace switching usable, as `SessionContext.logout` does today. The selector may show a pending state, but correctness does not depend on it.
   - **What is proven so far.** The spike proves the ordering, the serialized transitions and supersession with prototypes driven through the real `apiClient` and `httpClient`. Wiring the real providers and the hook is a production change, so it belongs to the implementation.
   - On `pagehide`, the client sends a best-effort `fetch(..., { keepalive: true, credentials: 'include' })` with the tenant and CSRF headers. If that is lost, the provider call simply runs to completion, as it does today.

## Requirements carried to the implementation

The spike's review produced guarantees that the prototype shows and that the production design must preserve and test.

- **Registry.** One lock for registration and cancellation. Tombstones have a TTL and a bounded store, keep one permission snapshot per cancel, and apply only if a single snapshot covers the operation.
- **Worker.**
  - A cancel and the publication of the attempt's outcome are decided by one atomic state transition.
  - The attempt's own result and its metadata surface to the request thread.
  - A failure is normalized to `CANCELLED` only when the cancel won.
  - The adapter checks for interruption in every catch branch.
- **Retry.**
  - The cancellation check lives in the `RetryGuard`, and `handle.start` remains the last line of defence.
  - A retry that the guard prevents is **recorded atomically as prevented**, in the same handle state that `handle.start` uses, so the registration reports `CANCELLED`. In the spike, a cancel during backoff that is stopped by the guard leaves the handle's `prevented` flag unset.
  - **Accounting only after an atomic start.** `ResilientAiProvider` must count `retryScheduled` and the attempt only after the decorator's atomic start succeeds. Alternatively, the decorator returns a distinct "not started" result that the resilience layer leaves out of its counters. Either way this is a change to `ResilientAiProvider`, because a check in the guard is not atomic with the accounting that follows.
- **Frontend.**
  - Every user-initiated tenant transition, including provisioning, goes through one generation-serialized coordinator that captures its source tenant.
  - Logout is **terminal while pending**: a switch or provisioning that starts while a logout is in flight is rejected, and transitions reopen only if the logout fails. An acceptable alternative is for the logout to drain the current tenant again just before it completes.
  - A tracker entry is owned by both the request and a pending supersede cancel.
  - A tenant drains: invocations tracked during a transition are cancelled too.
  - The **coordinator** closes the drain, not `cancelTenant`. It does so synchronously, at the moment it applies or abandons the transition, so no continuation can be queued between the end of the drain and `setCurrentTenantId`. In the spike, `cancelTenant` closes the drain one promise turn early.

## Limitations

- **Per instance.** The registry is in memory, like the rate limiter (ADR 0019 §7). A cancel that lands on another replica is a silent no-op. Supporting multiple instances would need sticky sessions or a shared registry, and a shared registry cannot hold the OkHttp call, so cancellation would have to become a notification to the owning node. This is deferred along with the other multi-instance work.
- **A cancel can be lost**, for example when a tab is closed abruptly. The call then completes as it does today, and the owning request removes its entry when it ends.
- **The provider-side saving is unknown.** The spike proves only that the **client** closes the transport. It does not show that OpenAI stops generating, or stops billing output tokens, when a non-streaming client disconnects. In the mid-body case the response had almost certainly been generated in full before the headers were sent. The permit stays consumed either way. What client-driven cancellation certainly saves is local work: the waiting request thread, retries, and the rest of the processing. Saving tokens may require OpenAI `background=true` with cancel-by-id, which is listed under the alternatives.
- **No monetary or token budget.** That concern is still separate, as in ADR 0019 §7.

## Alternatives considered

The alternatives from #178 still stand:

- streaming with heartbeats, which needs a new response contract;
- a different servlet container, whose behaviour is unverified;
- OpenAI `background=true` with cancel-by-id, which needs polling and temporary storage of the result.

The spike also rejected three simpler variants, because none of them closes the socket in time in every phase:

- interrupting the platform request thread;
- cancelling the top-level async future;
- a delegating `HttpClient` that cancels the inner HTTP future, which fails once the body is being read.

## Recommendation

On the client side, the approach is feasible and safe as designed above. However, ADR 0019 §7 is low severity, **no waste measurement exists yet**, and **the provider-side saving is unverified**. Before implementing:

1. Count the abandoned invocations: `GENERATED` outcomes whose result the frontend discarded. A cheap proxy is the existing local abort counted client-side, or the provider latency of calls that were not followed by a draft or an action.
2. Verify against a live call, or against OpenAI's documentation, whether closing the connection of a non-streaming Responses call stops generation and billing.
   - **If it does not,** client-driven cancellation saves only local work. Saving tokens would then require OpenAI `background=true` with cancel-by-id, and the alternatives should be re-evaluated.
3. Implement only if abandoned calls are a material share of provider cost **and** cancelling them actually reduces that cost.

Until then, ADR 0019 §7 stays the accepted behaviour.

## Consequences (if accepted)

- One new authorized endpoint, one optional request header, and an asynchronous `switchWorkspace` and logout ordering in the frontend.
- No provider cost saving is claimed until it is verified (see "Recommendation"). The certain saving is local: request threads, retries and processing.
- The cancellation depends on one JDK behaviour: an interrupt closes blocking socket I/O on a virtual thread. It also depends on OkHttp using blocking `java.net.Socket` I/O. A JDK or SDK upgrade must rerun `OpenAiCallCancellationSpikeTest`, or its production successor, in both phases.
- The audit vocabulary and the database constraints are unchanged.
