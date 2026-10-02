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

Only `TIMEOUT`, `THROTTLED` and `UNAVAILABLE` are `retryable()`. A *valid* explicit "no recommendation / no draft" outcome is a successful invocation, not a failure.

Client-facing results use the closed enum `AiUnavailableReason` (the categories above, `DISALLOWED_ACTION`, `DISALLOWED_TEMPLATE_INTENT`, `INVALID_RECOMMENDATION`, `CONTEXT_TOO_LARGE`, `CONTEXT_UNSUPPORTED`) and a derived boolean `retryable`. Provider or exception text never crosses the HTTP boundary.

### 2. Bounded retry, owned by one decorator

The SDK client keeps `maxRetries(0)`. `ResilientAiProvider` wraps the selected `AiProvider` and owns the only retry loop:

- configurable via `dokene.ai.retry.*`: `max-attempts` (default `2`, hard limit `3`, `1` disables), `initial-backoff` (250ms) and `max-backoff` (2s), both capped at 30s to keep the exponential backoff well inside the `long` range, `min-attempt-budget` (1s);
- the requested timeout is the **total deadline**: each attempt receives only the remaining budget, backoff is exponential with equal jitter, the deadline is re-checked after the backoff sleep (an overrun rethrows the last real failure instead of starting an attempt that cannot fit), and no attempt starts without `min-attempt-budget` left;
- retries only for `retryable()` categories and only around the provider call. Authorization revalidation, rate-limit acquisition and the Action Gate run in the caller before/after it, so retry cannot bypass them. A retry does not consume extra rate-limit quota but cannot exceed the deadline;
- `THROTTLED` is special: the adapter extracts the provider's `retry-after-ms`/`retry-after` (seconds) into `AiProviderException.retryAfter()` (a number only, never header text); the decorator waits exactly that long, and when the hint does not fit the remaining budget there is no retry. Without a hint it waits `max-backoff` rather than hammering a provider that is already rate limiting;
- any non-`AiProviderException` runtime failure is normalized to `UNAVAILABLE` without copying its message or cause; `UnsupportedOperationException` is the exception to that rule and maps to the permanent, never-retried `NOT_AVAILABLE`.

### 3. Telemetry through a port, Micrometer behind it

`AiTelemetry` is the port; `MicrometerAiTelemetry` implements it. Actuator is on the classpath **only** to provide the `MeterRegistry`; `management.endpoints.access.default` is `none` and no endpoint or exporter is configured, so no new diagnostics endpoint exists.

Meters (all `dokene.ai.*`): `attempts` and `attempt.duration` (one per provider attempt, retries included), `outcomes` (exactly one per AI invocation, i.e. per request that reaches the provider or fails context assembly: `GENERATED`, `MODEL_REFUSED`, `GATE_REJECTED`, `FAILED`; requests short-circuited as deterministically ineligible or rejected by authorization before any provider call are not AI invocations and produce none; use this for request counts and success rates), `tokens` (when the provider supplies usage), `retries`, `model.refusals`, `gate.rejections`. Tags come only from closed vocabularies: `operation`, `provider`, `model` (validated identifier), `outcome`, `category`, `direction`, `reason`. **Tenant, customer, actor, correlation id and free text are never tags**: this prevents unbounded cardinality and a cross-tenant information channel. The `reason` tag is enforced against a closed allow-list (`AiTelemetry.GATE_REJECTION_REASONS`, guarded by a test against `ActionGateRejectionReason`); any other value is recorded as `UNKNOWN`.

### 4. Privacy-safe durable audit

One event type, `AI_INVOCATION_OUTCOME`, targeting the `CUSTOMER`, with closed metadata `AiInvocation(operation, outcome, detail)`:

| AI outcome | audit outcome | detail |
|---|---|---|
| `GENERATED` | `SUCCESS` | `NONE` |
| `MODEL_REFUSED` | `SUCCESS` | `NONE` |
| `GATE_REJECTED` | `DENIED` | the exact `ActionGateRejectionReason`, except the tenant-boundary reasons below |
| `FAILED` | `FAILURE` | failure category or `CONTEXT_*` |

`NO_TENANT_CONTEXT`, `UNAUTHORIZED` and `CUSTOMER_NOT_FOUND` are raised at the authorization/existence boundary, before the requested customer is known to belong to the active tenant. They are counted and logged but **not audited** here (this includes the case where authorization is revoked or the customer disappears while a provider failure is in flight: the revalidation that aborts the failure path still reports the boundary rejection before the exception propagates): the caller-supplied customer id could be unverified or foreign and must not become an audit target (ADR 0006); the gate's own security-rejection audit already records them. They are not part of the audit vocabulary (`AiAuditDetail`).

This is the first use of `FAILURE`. Migration `V13` adds the constrained columns `ai_operation`, `ai_outcome`, `ai_detail`, extends `ck_audit_shape`, forbids those columns on every other event type (`ck_audit_ai_columns`) and replaces `dokene.append_audit_event` with a 16-argument signature. No prompt, generated text, note, phone number or provider message has a column to land in. Migration `V14` (additive, so an already-applied `V13` is never edited) closes two gaps in the V13 constraint: a CHECK passes when its expression is UNKNOWN, so the three AI columns are now explicitly required to be non-null (`ck_audit_ai_not_null`), and the three tenant-boundary details are rejected by the database (`ck_audit_ai_detail_verified_target`). Events are written with `REQUIRES_NEW` because failed and rejected invocations have no business transaction; audit failure keeps the ADR 0006 behaviour (generic 503, no retry). The existing security-rejection audit from ADR 0017 is unchanged; the new event adds the unlossy gate reason. The follow-up module depends only on `AiOutcomeAuditPort`; the audit module implements it (same one-way dependency as the customer/purchase adapters).

### 5. Logs and correlation

`AiOutcomeReporter` is the single terminal reporting point (metrics, one log line, one audit event). The log line contains `operation`, `outcome`, closed `detail` and `correlationId` only. `AuditRequestFilter` additionally places the server-generated correlation UUID in `MDC["correlationId"]` (cleared in `finally`), echoes it as the `X-Request-Id` response header, and the log pattern includes it. Inbound `X-Request-Id` remains ignored (ADR 0006). The OpenAI adapter forwards the same UUID as `X-Client-Request-Id` for provider-side diagnostics. It carries no PII. CORS exposes `X-Request-Id` (next to `ETag`) so a browser client can show it when reporting a problem, and the log pattern uses Spring Boot's `logging.pattern.correlation` hook. The terminal outcome is reported once the result is decided: output discarded by an `If-Match` conflict or a policy-version drift is audited as `GATE_REJECTED`/`STALE_STATE`, never as `GENERATED`.

### 6. HTTP semantics

AI failure stays an HTTP 200 `AI_UNAVAILABLE` result with `unavailableReason` and `retryable`; the evaluation and the deterministic queue remain usable. Authorization, not-found, conflict and rate-limit semantics are unchanged. A stray `AiProviderException` maps to an empty 503 and an unexpected `IllegalStateException` to an empty 500. Both are logged without the message or cause (category, or exception class and top stack frame), so the failure stays diagnosable without leaking text.

## Consequences

- Provider outages, throttling, malformed output and refusals are observable and distinguishable without storing any sensitive content; operators and clients get stable, documented recovery semantics.
- Clients should retry only when `retryable` is `true`, with backoff, and may continue the manual workflow at any time.
- A transient failure can cost at most `max-attempts` provider invocations per request (default 2); the cost is bounded by the request timeout.
- `UNAVAILABLE` is no longer reported for a disabled provider; clients now see `NOT_AVAILABLE` (`retryable=false`).
- Audit volume grows by one row per terminal AI invocation. Because audit is fail-closed (ADR 0006), an audit outage makes the AI endpoints return 503 instead of silently losing the record.
- A `MeterRegistry` exists but nothing exports it; shipping metrics to a dashboard (Phase 4) is a separate decision that must keep the tag allow-list above.

## Out of scope

Operational dashboards, messaging-provider health, global scheduling, raw prompt/response storage, automatic provider failover and any public diagnostics endpoint.
