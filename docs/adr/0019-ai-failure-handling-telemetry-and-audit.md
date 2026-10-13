# ADR 0019: AI Failure Handling, Telemetry and Privacy-Safe Audit

## Status

Accepted. Amends [ADR 0016](0016-openai-responses-api-adapter-with-structured-outputs.md) (retry clause and failure taxonomy), [ADR 0017](0017-deterministic-ai-action-gate.md) §5 (audit granularity) and [ADR 0006](0006-durable-append-only-audit.md) (event vocabulary, first use of `FAILURE`).

## Context

[Issue #96](https://github.com/stevdrey/dokene/issues/96). Next Best Action ([ADR 0015](0015-structured-next-best-action-recommendation-contracts.md)) and message drafting ([ADR 0018](0018-constrained-follow-up-message-draft-generation.md)) already degrade to `AI_UNAVAILABLE`, but the failure path was opaque and brittle:

- provider metadata (latency, tokens, model) was discarded; there was no logger, metric or audit of AI outcomes;
- retries were forbidden and "provider disabled" was indistinguishable from "provider down";
- a model `refusal` content part surfaced as a malformed response; unexpected runtime exceptions escaped as HTTP 500;
- gate rejections were audited only as a lossy `INSUFFICIENT_PERMISSION` denial, and generated, refused and failed outcomes were not recorded;
- `unavailableReason` was free-form text and the request correlation id never reached logs, responses or the provider.

AI is advisory and untrusted (ADR 0004). Operational visibility must not create a data-leak channel, and a provider problem must never make the deterministic follow-up queue unusable.

## Decision

### 1. Stable failure taxonomy

`AiFailureCategory` is the single provider-neutral vocabulary: `TIMEOUT`, `THROTTLED`, `UNAVAILABLE`, `INVALID_STRUCTURED_RESPONSE`, `REJECTED_REQUEST`, `CANCELLED`, plus two new categories:

- `NOT_AVAILABLE`: AI is disabled or the operation is unsupported by the configured provider (previously folded into `UNAVAILABLE`). Never retried.
- `REFUSED`: the model returned a refusal content part and no usable output. The refusal text is never read or retained. Never retried.

A local `CANCELLED` is a cancellation observed by the provider stack: an interrupted worker thread, an `InterruptedException` (including one raised during the retry backoff sleep in `ResilientAiProvider`) or a `CancellationException` thrown by the SDK call (the last can occur while the interrupt flag is clear). A provider response with status `CANCELLED` maps to the same category without any interrupt and is a separate cause. Both are audited as `FAILURE`/`CANCELLED` for auditable invocations (§4).

Only `TIMEOUT`, `THROTTLED` and `UNAVAILABLE` are `retryable()`. A *valid* explicit "no recommendation / no draft" outcome is a successful invocation, not a failure.

Client-facing results use the closed enum `AiUnavailableReason` (the categories above, `DISALLOWED_ACTION`, `DISALLOWED_TEMPLATE_INTENT`, `INVALID_RECOMMENDATION`, `CONTEXT_TOO_LARGE`, `CONTEXT_UNSUPPORTED`) and a derived boolean `retryable`. Provider or exception text never crosses the HTTP boundary.

### 2. Bounded retry, owned by one decorator

The SDK client keeps `maxRetries(0)`. `ResilientAiProvider` wraps the selected `AiProvider` and owns the only retry loop:

- configurable via `dokene.ai.retry.*`: `max-attempts` (default `2`, hard limit `3`, `1` disables), `initial-backoff` (250ms) and `max-backoff` (2s), both capped at 30s to keep the exponential backoff well inside the `long` range, `min-attempt-budget` (1s);
- the requested timeout is the **total deadline**: each attempt receives only the remaining budget, backoff is exponential with equal jitter, the deadline is re-checked after the backoff sleep (an overrun rethrows the last real failure instead of starting an attempt that cannot fit), and no *retry* starts without `min-attempt-budget` left after the backoff. The first attempt always runs with the requested timeout: the minimum budget only gates retries, so an explicit short client timeout (e.g. `timeoutMs=100`) is honored as given instead of being silently raised or suppressed;
- retries only for `retryable()` categories and only around the provider call. Authorization revalidation, rate-limit acquisition and the Action Gate run in the caller before/after it, so retry cannot bypass them. Because the loop is provider-only it knows nothing about tenants, so the caller passes a `RetryGuard` that **re-validates authorization before every retry** (never before the first attempt, which the caller already validated): if access was revoked or the customer disappeared between attempts, the guard throws, the retry is aborted before the customer context is sent again, the exception propagates unchanged (403/404) and the invocation is still counted and logged as a tenant-boundary rejection (no audit row, see below). The same guard also re-runs the deterministic customer-level constraints the Action Gate applies to the result (`AiActionGate.retryBlockingReason`: archived, do-not-contact, granted WhatsApp consent, follow-up eligibility and drift against the baseline assembly, including the policy version): if the customer stopped being eligible while the first attempt was in flight, the retry is abandoned with no further provider request, the invocation is audited as `GATE_REJECTED` with the specific reason ([Issue #185](https://github.com/stevdrey/dokene/issues/185)) and the caller receives the same `INELIGIBLE`/`STALE_STATE` result as for a deterministic rejection (HTTP 200), or 409 when the request's `If-Match` no longer matches the fresh policy version, exactly as on the post-provider path. The check is fail-closed: any drift of the baseline, including the tenant date rolling over during the backoff, blocks the retry as `STALE_STATE`, which is what the gate would decide for the result anyway. Authorization and eligibility are read in one transaction (`AiActionGate.revalidateBeforeRetry`). The first attempt was legitimately sent before the change; only the retry is prevented. The `retries` counter only counts retries that were actually performed. A retry does not consume extra rate-limit quota but cannot exceed the deadline;
- `THROTTLED` is special: the adapter extracts the provider's `retry-after-ms`/`retry-after` (seconds) into `AiProviderException.retryAfter()` (a number only, never header text); the decorator waits exactly that long, and when the hint does not fit the remaining budget there is no retry. Without a hint it waits `max-backoff` rather than hammering a provider that is already rate limiting;
- any non-`AiProviderException` runtime failure is normalized to `UNAVAILABLE` without copying its message or cause; `UnsupportedOperationException` is the exception to that rule and maps to the permanent, never-retried `NOT_AVAILABLE`.

### 3. Telemetry through a port, Micrometer behind it

`AiTelemetry` is the port; `MicrometerAiTelemetry` implements it. Actuator is on the classpath **only** to provide the `MeterRegistry`; `management.endpoints.access.default` is `none` and no endpoint or exporter is configured, so no new diagnostics endpoint exists.

Meters (all `dokene.ai.*`): `attempts` and `attempt.duration` (one per provider attempt, retries included), `outcomes` (tagged `operation`, `provider`, `model` and terminal `outcome`, so "how many `NEXT_BEST_ACTION` invocations used `openai`/`gpt-6-luna` and ended `GENERATED`" is answerable without summing attempts; provider/model come from the validated `AiInvocationMetadata` of the terminal provider call, i.e. the successful response or the terminal failure, or the previous attempt when the pre-retry guard aborts; `none`/`none` when no provider was reached, e.g. context assembly failed; a disabled provider reports `disabled`/`none`; exactly one per AI invocation, i.e. per request that reaches the provider or fails context assembly: `GENERATED`, `MODEL_REFUSED`, `GATE_REJECTED`, `FAILED`; requests short-circuited as deterministically ineligible or rejected by authorization before any provider call are not AI invocations and produce none; use this for request counts and success rates), `tokens` (when the provider supplies usage), `retries`, `model.refusals`, `gate.rejections`. Tags come only from closed vocabularies: `operation`, `provider`, `model` (validated identifier), `outcome`, `category`, `direction`, `reason`. **Tenant, customer, actor, correlation id and free text are never tags**: this prevents unbounded cardinality and a cross-tenant information channel. The `reason` tag is enforced against a closed allow-list (`AiTelemetry.GATE_REJECTION_REASONS`, guarded by a test against `ActionGateRejectionReason`); any other value is recorded as `UNKNOWN`.

### 4. Privacy-safe durable audit

One event type, `AI_INVOCATION_OUTCOME`, targeting the `CUSTOMER`, with closed metadata `AiInvocation(operation, outcome, detail)`:

| AI outcome | audit outcome | detail |
|---|---|---|
| `GENERATED` | `SUCCESS` | `NONE` |
| `MODEL_REFUSED` | `SUCCESS` | `NONE` |
| `GATE_REJECTED` | `DENIED` | the exact `ActionGateRejectionReason`, except the tenant-boundary reasons below |
| `FAILED` | `FAILURE` | failure category or `CONTEXT_*` |

`NO_TENANT_CONTEXT`, `UNAUTHORIZED` and `CUSTOMER_NOT_FOUND` are raised at the authorization/existence boundary, before the requested customer is known to belong to the active tenant. They are counted and logged but **not audited** here (this includes the case where authorization is revoked or the customer disappears while a provider failure is in flight: the revalidation that aborts the failure path still reports the boundary rejection before the exception propagates): the caller-supplied customer id could be unverified or foreign and must not become an audit target (ADR 0006); the gate's own security-rejection audit already records them. They are not part of the audit vocabulary (`AiAuditDetail`).

This is the first use of `FAILURE`. Migration `V13` adds the constrained columns `ai_operation`, `ai_outcome`, `ai_detail`, extends `ck_audit_shape`, forbids those columns on every other event type (`ck_audit_ai_columns`) and replaces `dokene.append_audit_event` with a 16-argument signature. No prompt, generated text, note, phone number or provider message has a column to land in. Migration `V14` (additive, so an already-applied `V13` is never edited) closes two gaps in the V13 constraint: a CHECK passes when its expression is UNKNOWN, so the three AI columns are now explicitly required to be non-null (`ck_audit_ai_not_null`), and the three tenant-boundary details are rejected by the database (`ck_audit_ai_detail_verified_target`). Migration `V15` (also additive) requires the AI target to be a `CUSTOMER` with a non-null `target_type` (`ck_audit_ai_target_type`): the V13 branch compared `target_type` with `=`, which is UNKNOWN for NULL, so a row without a target type was accepted and would break the reader's `AuditTarget` mapping. Events are written with `REQUIRES_NEW` because failed and rejected invocations have no business transaction; audit failure keeps the ADR 0006 behaviour (generic 503, no retry). The existing security-rejection audit from ADR 0017 is unchanged; the new event adds the unlossy gate reason. The follow-up module depends only on `AiOutcomeAuditPort`; the audit module implements it (same one-way dependency as the customer/purchase adapters).

### 5. Logs and correlation

`AiOutcomeReporter` is the single terminal reporting point (metrics, one log line, one audit event). The log line contains `operation`, `outcome`, closed `detail` and `correlationId` only. `AuditRequestFilter` additionally places the server-generated correlation UUID in `MDC["correlationId"]` (cleared in `finally`), echoes it as the `X-Request-Id` response header, and the log pattern includes it. Inbound `X-Request-Id` remains ignored (ADR 0006). The OpenAI adapter forwards the same UUID as `X-Client-Request-Id` for provider-side diagnostics. It carries no PII. CORS exposes `X-Request-Id` (next to `ETag`) so a browser client can show it when reporting a problem, and the log pattern uses Spring Boot's `logging.pattern.correlation` hook. The terminal outcome is reported once the result is decided: output discarded by an `If-Match` conflict or a policy-version drift is audited as `GATE_REJECTED`/`STALE_STATE`, never as `GENERATED`.

### 6. HTTP semantics

AI failure stays an HTTP 200 `AI_UNAVAILABLE` result with `unavailableReason` and `retryable`; the evaluation and the deterministic queue remain usable. Authorization, not-found, conflict and rate-limit semantics are unchanged. A stray `AiProviderException` maps to an empty 503 and an unexpected `IllegalStateException` to an empty 500. Both are logged without the message or cause (category, or exception class and top stack frame), so the failure stays diagnosable without leaking text.

### 7. Client abandonment is not detected (known limitation)

[Issue #178](https://github.com/stevdrey/dokene/issues/178). Nothing interrupts the worker thread when the browser aborts a request (customer switch, workspace switch, logout), so client abandonment never produces a local `CANCELLED` (see §1 for what does). An abandoned recommendation or draft call that has reached the provider **runs to completion**, is reported with its real terminal outcome (typically `GENERATED`) and counts against `dokene.ai.rate-limit.*`; the provider call and its cost are not avoided, and the user interface only discards the late result. An abandoned request that is still in pre-provider work can finish without a provider call or quota use (a stale `If-Match`, deterministic ineligibility, or a rate-limit rejection), so these consequences apply only to invocations that reached the rate-limit/provider boundary. Where an invocation is auditable its outcome is audited as in §4; if authorization is revoked or the customer disappears meanwhile, the boundary rejection is reported and no audit row is written.

Cancelling the abandoned call was prototyped and rejected. The only way to learn about a disconnect without writing to the response is the servlet async error callback, and Tomcat does not raise it for a pending async request whose body was already read. Minimal reproduction: embedded Tomcat 11, a controller returning a `DeferredResult` completed by a blocked virtual-thread worker, a raw socket that sends the `GET` and then closes; no `AsyncListener.onError` arrived within 10s. Tomcat only notices the closed connection on a write, and committing the response early is not possible here because the status code (404/409/403) and `ETag` depend on the result. The prototype test was not kept because it asserts a negative that a container upgrade could change; rerunning this scenario is the first step of any revisit.

`GENERATED` means the provider generated a result that passed validation; it says nothing about whether the browser received or used it. Delivery is unknown and is deliberately not inferred from a missing confirmation (even a successful HTTP write does not prove the user saw the result). Measuring reception would be a possible future option, not a decision: it would need an explicit frontend acknowledgement, its own contract and an audit vocabulary for "unknown" (the closed `AiAuditDetail` has no such value), and it must not be recorded as `CANCELLED`.

The current limits bound call frequency (`dokene.ai.rate-limit.*`, a per-process, in-memory window that each backend replica keeps separately and a restart clears, so it is not fleet-wide or durable spend protection; at most two provider attempts per invocation by default, three at the configured hard maximum `dokene.ai.retry.max-attempts=3`) but are not a monetary budget: they do not cap output tokens or spend. A spend or token budget is a separate concern from this limitation.

Revisit this if the servlet container changes or the endpoint moves to a streaming response contract. The evaluated alternative, explicit client-driven cancellation, is tracked in [Issue #180](https://github.com/stevdrey/dokene/issues/180).

## Consequences

- An operator who abandons a request that already reached the provider still pays for the call, and the per-actor/tenant rate limits count it. An auditable invocation keeps its real terminal outcome, typically `GENERATED`, for a result nobody saw (it could equally be `FAILED`, `MODEL_REFUSED` or `GATE_REJECTED`, e.g. `STALE_STATE` if the policy version moved meanwhile; §7). The tenant-boundary rejections (`UNAUTHORIZED`, `CUSTOMER_NOT_FOUND`, e.g. access revoked while the call was in flight) are counted and logged but have no audit row (§4).
- Provider outages, throttling, malformed output and refusals are observable and distinguishable without storing any sensitive content; operators and clients get stable, documented recovery semantics.
- Clients should retry only when `retryable` is `true`, with backoff, and may continue the manual workflow at any time.
- A transient failure can cost at most `max-attempts` provider invocations per request (default 2); the cost is bounded by the request timeout.
- `UNAVAILABLE` is no longer reported for a disabled provider; clients now see `NOT_AVAILABLE` (`retryable=false`).
- Audit volume grows by one row per terminal AI invocation. Because audit is fail-closed (ADR 0006), an audit outage makes the AI endpoints return 503 instead of silently losing the record.
- A `MeterRegistry` exists but nothing exports it; shipping metrics to a dashboard (Phase 4) is a separate decision that must keep the tag allow-list above.

## Out of scope

Operational dashboards, messaging-provider health, global scheduling, raw prompt/response storage, automatic provider failover and any public diagnostics endpoint.
