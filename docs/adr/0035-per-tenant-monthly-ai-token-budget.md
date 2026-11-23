# ADR 0035: Per-Tenant Monthly AI Token Budget

## Status

Accepted. Issue: [#346](https://github.com/stevdrey/dokene/issues/346), part of epic [#305](https://github.com/stevdrey/dokene/issues/305). Follow-up to audit finding [L10](../security/security-audit-2026-11-02.md) and [#316](https://github.com/stevdrey/dokene/issues/316).

It extends [ADR 0019](0019-ai-failure-handling-telemetry-and-audit.md) (failure taxonomy and audit) with one new reason; ADR 0019 is otherwise unchanged.

## Context

[#316](https://github.com/stevdrey/dokene/issues/316) caps each OpenAI request with `max_output_tokens`, and the AI rate limit (`dokene.ai.rate-limit`) bounds how many calls run per minute, in memory and per instance. Neither bounds what a tenant spends over time: at the default rate limit a single tenant can run tens of thousands of provider calls a day, and every one is billed to the deployment. The provider already reports the input and output tokens of each attempt in `AiInvocationMetadata.usage()`, failed and incomplete attempts included.

## Decision

1. **Unit and window: tokens per UTC calendar month.** The budget is an admission threshold on the sum of provider-reported input and output tokens a tenant has *recorded* from the first day of a UTC calendar month to the last: a new invocation is admitted only while that sum is below the threshold (§6 explains why this is not a ceiling). Tokens are what the provider reports and bills; an estimated cost would need a per-model price table the application does not own and that changes without a release. A calendar month matches the billing period and gives a predictable reset; a rolling window would need per-call history instead of one counter.
2. **One limit, set by the deployment operator.** `dokene.ai.token-budget.monthly-tokens` (environment `DOKENE_AI_TOKEN_BUDGET_MONTHLY_TOKENS`, default `2000000`) applies to every tenant separately. It must be positive: zero or a negative value stops startup, so a typo can never switch the budget off. Tenant members, owners included, can neither see nor change the limit or the counter; there is no API for either. Per-tenant overrides and a tenant-visible usage view are out of scope (see below).
3. **Durable, shared storage.** Usage lives in `dokene.ai_token_usage` (V42), one row per tenant and month, so it holds across instances and restarts. The row is incremented by a single `INSERT … ON CONFLICT DO UPDATE` adding the new counts, so concurrent requests on any instance never lose an update. The table has forced row-level security on the verified tenant; the runtime role may select, insert and update its own tenant's rows and may not delete. The upsert needs `UPDATE`, so a `BEFORE UPDATE` trigger owned by the migration role (`ai_token_usage_only_increases`) rejects any decrease and any change of tenant or month; the runtime role cannot drop or disable it. The application therefore cannot reset a counter. Correcting or resetting a counter is an operator action under the migration role.
4. **What counts: every attempt the provider reports.** A metering decorator wraps the raw provider *inside* the retry decorator, so the usage reported by each attempt is counted, whether the attempt succeeded, failed, returned an incomplete or invalid response, or was retried, because the provider bills all of them. An attempt that reports no usage (a transport failure before a response) adds nothing. Usage is accumulated in memory during the invocation and written once when the provider call returns or fails, so the retry loop never waits on the database and a database error is never mistaken for a retryable provider failure. A write failure fails the request like any database outage (ADR 0029) unless the provider call itself failed, whose exception then wins; the tokens that could not be recorded are then logged as a warning (counts and failure type only).
5. **At the limit: degrade with no provider call.** After the AI rate limit (so an exhausted tenant's refusals are still throttled and cannot write audit rows without bound) and before any provider call, the next-best-action and draft services read the tenant's counter for the current month. At or over the limit they make no provider call (no customer context leaves the system) and return the existing `AI_UNAVAILABLE` result with the new closed reason `TOKEN_BUDGET_EXHAUSTED` (`retryable: false`). The deterministic evaluation, queue, manual follow-up and messaging workflows stay available. The refusal is reported like any AI failure (ADR 0019): the `FAILED` outcome metric with provider and model `none`, one log line with detail `TOKEN_BUDGET_EXHAUSTED`, and one durable `AI_INVOCATION_OUTCOME` audit row with that detail, which is the operator-visible reason. V42 widens `ck_audit_shape` to accept it.
6. **An admission threshold, not a ceiling.** The budget decides whether a new invocation may start; it does not guarantee a ceiling on a tenant's total tokens or spend. The recorded total can end above the threshold, and spend can exceed what is recorded:
   - The check and the write are not one transaction. Every invocation admitted while the tenant was below the threshold runs to completion and is counted, so the last admitted invocations can push the total past it, and so can concurrent ones admitted before any of them was recorded.
   - Nothing caps one invocation's total: `max_output_tokens` caps the output of one attempt only, while the meter also sums input tokens, which depend on the context sent, and an invocation can take up to 3 attempts.
   - The AI rate limit that bounds how many invocations can be in flight is held in memory per instance, so with several instances the possible overshoot grows with the instance count.
   - An attempt that reports no usage (§4), such as a client timeout after which the provider may keep generating and billing, adds nothing to the recorded total.

   A strict ceiling would need a reservation of an estimated amount, or a row lock held across a provider call of up to 30 seconds, and still could not see unreported usage; the admission threshold is accepted instead. Operators should set it with headroom below what they can afford, and reconcile with the provider's own usage reports.

## Consequences

- A disabled provider (`AiProvider.enabled()` is false) bills nothing, so the budget is not read for it and AI that is switched off keeps reporting `NOT_AVAILABLE`.

- A tenant over budget sees `AI_UNAVAILABLE` / `TOKEN_BUDGET_EXHAUSTED` until the next UTC month; the SPA explains that the monthly AI limit was reached and offers the manual workflow.
- Each AI invocation adds one indexed read before the provider call and at most one upsert after it.
- The budget is not a spend guarantee (§6): it bounds how long a tenant can keep starting AI work in a month, not the exact tokens or money that work consumes.
- The month is fixed when the invocation starts: a call spanning the month boundary is charged to the month it started in.
- Old monthly rows are kept. They are small (one per tenant per month) and serve as usage history; there is no retention job.
- The budget counts tokens, not money: a model change alters the cost of a token, and the limit may need revisiting with it.

## Out of scope

- Per-tenant limits, a tenant-visible usage view, and an admin API to change or reset a counter. They need an authorization and audit design of their own; the counter table already supports them.
- Estimated-cost budgets and alerts before the limit is reached.
- Budgets for future AI operations. Follow-up services reach the provider only through `AiInvocationRunner`, which applies the rate limit, opens the budget and meters every attempt; an ArchUnit rule fails the build if another follow-up class calls `AiProvider.recommend` or `draft` directly. An AI operation outside the follow-up module would need the same guard.
