# AI and Automation

## Role of AI in Dokene

AI is used where language generation, contextual judgment, and recommendation quality provide meaningful value.

AI is not the security model, authorization engine, consent engine, scheduler lock, or source of truth for customer state.

The application should remain useful even when an AI provider is temporarily unavailable.

## Next Best Action

A long-term product capability is a **Next Best Action** recommendation for each customer.

Inputs may include:

- customer history;
- recent purchases;
- elapsed time since activity;
- configured follow-up cadence;
- product relationships;
- seasonal/special dates;
- prior communication outcomes;
- tenant-specific business rules;
- consent/contact eligibility.

Outputs should be structured, explainable, and constrained.

Example conceptual response:

```text
FollowUpDecision
├── evaluation: FollowUpEvaluation
│   ├── status: DUE
│   └── reasons: [DUE_TODAY]
└── recommendation: ActionRecommendation
    ├── action: REPEAT_PURCHASE_FOLLOW_UP
    ├── templateIntent: REPEAT_PURCHASE
    ├── rationale: "Customer bought a repeat-purchase item 63 days ago"
    ├── confidence: 0.84
    └── draftVariables: {...}
```

The exact schema is governed by [ADR 0015: Structured Next Best Action Recommendation Contracts](../adr/0015-structured-next-best-action-recommendation-contracts.md), which strictly separates deterministic evaluation facts (from `FollowUpEvaluation`) from advisory, untrusted AI output (`RecommendationOutcome`). Free-form model text must not be interpreted directly as an arbitrary command.

## Recommendation Context Classification

The application assembles recommendation context for one authorized customer before any provider call. Its
`trusted` section contains tenant-local date, follow-up status and reasons, effective cadence, due date,
contact eligibility, up to five valid purchase timestamps, and the fixed application action vocabulary when
the customer is due or overdue. These fields come from deterministic application services; the context does
not carry tenant, customer, contact, or purchase IDs.

The separate `untrusted` section contains display name, optional customer notes, and descriptions paired by
position with the purchase timestamps. These are customer/business text, including imported text, and must be
rendered as data by any future provider adapter. They cannot provide instructions, tool calls, authorization,
tenant identity, or action policy. Each text field is limited to 500 Java characters; combined text is limited
to 2,500 characters. The assembler selects at most five valid purchases, ordered by purchase time and ID
descending; no other history is included. A context payload with more than five entries or mismatched lists
is rejected with a typed, content-free application error. Excess text fails rather than being truncated.

Phone numbers, audit data, internal IDs, credentials, integration settings, and free-form tenant rules are
excluded. The assembler does not log context. Provider adapters must preserve the structured trust separation
and must not log raw requests or responses.

## AI Action Gate

All AI-recommended side effects must cross an application-controlled gate.

```text
Application context
      ↓
AI provider
      ↓
Structured result
      ↓
Schema validation
      ↓
Business eligibility
      ↓
Consent/contact policy
      ↓
Authorization
      ↓
Action allowlist
      ↓
Human approval or approved automation policy
      ↓
Side effect
```

The model may recommend `REPEAT_PURCHASE`, but application code determines whether that action exists, is allowed for the tenant, is valid for the recipient, and may be sent now.

Under [ADR 0017: Deterministic AI Action Gate for Recommendations and Drafts](../adr/0017-deterministic-ai-action-gate.md), the `AiActionGate` (`DefaultAiActionGate` in `io.github.stevdrey.dokene.followup.application`) operationalizes this gate deterministically before any recommendation or draft is accepted or exposed to operators:

1. **Active Tenant Context**: Ensures an authenticated tenant context exists; rejects with `NO_TENANT_CONTEXT` if missing.
2. **Caller Authorization**: Verifies caller permission `TenantPermission.FOLLOWUP_EVALUATE`; rejects with `UNAUTHORIZED` if absent.
3. **Customer Existence & Status**: Looks up customer in active tenant; rejects with `CUSTOMER_NOT_FOUND` if absent or `CUSTOMER_ARCHIVED` if inactive.
4. **Contact Policy & Consent**: Verifies WhatsApp channel consent and opt-out state; rejects with `DO_NOT_CONTACT` or `NO_CONTACT_CONSENT`.
5. **Fresh Eligibility & Due Status**: Evaluates authoritative follow-up status; rejects with `FOLLOW_UP_INELIGIBLE` if not `DUE` or `OVERDUE`.
6. **Stale State Detection**: Compares against the baseline assembly state (`lastPurchaseId`, `lastPurchaseTime`, `effectiveCadenceDays`); rejects with `STALE_STATE` if drift occurred since context assembly.
7. **Semantic Action & Template Intent Allowlists**: Revalidates allowed actions and intent compatibility; rejects with `DISALLOWED_ACTION` or `DISALLOWED_TEMPLATE_INTENT`.

The gate produces a sealed `ActionGateDecision` (`Accepted` or `Rejected` with typed `ActionGateRejectionReason`). When rejected, `FollowUpDecision` hides action recommendations (`advisoryRecommendation()` returns empty, `hasActionRecommendation()` is false). The evaluation executes with zero external side effects and records privacy-safe security observability metrics via `AiActionGateAuditListener` (strictly excluding customer notes, prompt text, or PII).

## Next Best Action Recommendation Orchestration & API

Recommendation orchestration is coordinated by `FollowUpRecommendationService` and exposed via tenant-scoped REST endpoints:
- `POST /api/customers/{customerId}/recommendation`
- `POST /api/customers/{customerId}/follow-up-recommendation` (canonical alias)

### Headers & Optimistic Locking
- Requires authenticated tenant context and `TenantPermission.FOLLOWUP_EVALUATE` (returns `403 Forbidden` if denied).
- Supports optional `If-Match: "<version>"` (strong quoted numeric entity tag). If the customer policy version does not match, returns `409 Conflict`.
- Successful responses return `ETag: "<version>"` matching the evaluated customer policy version.

### Response Statuses & Semantics
The response body returns a top-level `status` enum (`RecommendationStatus`):
- `AVAILABLE`: Valid action recommendation approved through `AiActionGate`. Contains advisory action, template intent, rationale, and draft variables.
- `NO_RECOMMENDATION`: AI provider evaluated the context and determined no follow-up action is needed at this time. Preserves advisory refusal reason, rationale, and confidence in `refusal`.
- `STALE_STATE`: Customer purchase history or cadence state drifted between context assembly and gate evaluation.
- `INELIGIBLE`: Customer is not currently due/overdue, has no WhatsApp consent/opt-out, or is archived.
- `AI_UNAVAILABLE`: Provider call timed out, threw an exception, or failed schema validation. Degrades gracefully with zero raw provider error payloads or stack traces exposed to clients.

### Invariants
- **Zero Business Side Effects**: Orchestration is strictly read-only decision support. It never modifies customer policy versions, dismissals, manual follow-up dates, or due queue state.
- **Separation of Due Reasons vs. AI Rationale**: Authoritative deterministic triggers (`evaluation.reasons`, such as `DUE_TODAY` or `OVERDUE`) remain cleanly separated from advisory AI explanations (`recommendation.rationale`).

## Constrained Follow-Up Message Draft Generation

Following next-best-action evaluation, Dokene supports constrained follow-up message draft generation, governed by [ADR 0018: Constrained Follow-Up Message Draft Generation](../adr/0018-constrained-follow-up-message-draft-generation.md).

### Grounding & Safety Constraints
- **Dedicated Permission**: Requires `TenantPermission.MESSAGE_DRAFT` in addition to `FOLLOWUP_EVALUATE`.
- **Zero Fabrication**: Prohibits invented discounts (e.g. percentages or discount terms not in purchase history/notes), prices/currencies, external URLs, and provider template IDs.
- **Language**: Default wording in Latin American Spanish (`es-419`).
- **Grounded Facts**: Bound to authoritative tenant business name (`TrustedBusinessFacts`) and validated customer context (`DraftContext`).
- **Deterministic Gate Check**: Re-runs `evaluateDraft` on `AiActionGate` prior to returning the draft, verifying customer eligibility, absence of stale state, and lack of prohibited terms.

### Draft Generation Endpoints
- `POST /api/customers/{customerId}/draft`
- `POST /api/customers/{customerId}/follow-up-draft` (alias)

Supports optional `If-Match: "<version>"` header and returns `ETag: "<version>"` on success. Returns `DraftResponse` with statuses: `AVAILABLE`, `NO_DRAFT`, `INELIGIBLE`, `STALE_STATE`, `AI_UNAVAILABLE`.

### Phase 2 to Phase 3 Handoff
Draft generation is strictly an advisory decision-support operation. Generated message drafts are returned to human operators in the UI for review. Outbound messaging delivery, WhatsApp Cloud API dispatch, and operator approval state machines (`MESSAGE_APPROVE`, `MESSAGE_SEND`) belong to Phase 3 and are decoupled from draft generation.

## AI Failure Handling, Telemetry and Audit

Governed by [ADR 0019](../adr/0019-ai-failure-handling-telemetry-and-audit.md). AI assistance is optional: **the deterministic follow-up queue and manual dispositions work identically whether the provider is healthy, degraded, disabled or failing.**

### Client-facing recovery semantics

Recommendation and draft requests do not fail with an error status when the AI step cannot help. They return HTTP 200 with `status: AI_UNAVAILABLE`, the unchanged deterministic `evaluation`, an `unavailableReason` and a `retryable` hint:

| `unavailableReason` | `retryable` | Meaning and suggested handling |
|---|---|---|
| `TIMEOUT`, `THROTTLED`, `UNAVAILABLE` | `true` | Transient provider problem that survived the server-side bounded retry. Offer "try again" with backoff; continue the manual workflow meanwhile. |
| `NOT_AVAILABLE` | `false` | AI is disabled or unsupported. Hide or disable AI actions; nothing to retry. |
| `REFUSED`, `INVALID_STRUCTURED_RESPONSE`, `REJECTED_REQUEST`, `CANCELLED` | `false` | The provider could not produce a usable result. Fall back to the manual workflow. |
| `DISALLOWED_ACTION`, `DISALLOWED_TEMPLATE_INTENT`, `INVALID_RECOMMENDATION` | `false` | The deterministic Action Gate rejected model output. Never shown as advice. |
| `CONTEXT_TOO_LARGE`, `CONTEXT_UNSUPPORTED` | `false` | The customer context cannot be sent to the model safely. |

Other statuses keep their meaning: `403` (not authorized), `404` (customer not in tenant), `409` (stale `If-Match`), `429` + `Retry-After` (per-tenant/actor AI rate limit). An unexpected server error returns an empty `500`/`503` body: provider and exception text never reaches the client.

### Retry

Only `TIMEOUT`, `THROTTLED` and `UNAVAILABLE` are retried, only around the provider call, and the requested timeout is the total deadline. Configure with `dokene.ai.retry.max-attempts` (default `2`, maximum `3`, `1` disables), `initial-backoff`, `max-backoff` and `min-attempt-budget` (the smallest budget a *retry* may start with; the first attempt always runs with the requested timeout). Authorization and the Action Gate are never skipped by a retry. On `THROTTLED` the provider's `Retry-After` is honored (no retry if it exceeds the remaining deadline); without one the wait is `max-backoff`. Backoff values are capped at 30s.

### Metrics

Under `dokene.ai.*`: `attempts` and `attempt.duration` (per provider attempt, retries included), `outcomes` (one per AI invocation; use it for request counts and success rate), `tokens` (only when the provider reports usage), `retries`, `model.refusals`, `gate.rejections`. Tags are limited to `operation`, `provider`, `model`, `outcome`, `category`, `direction`, `reason`. No tenant, customer, actor or correlation tags, and no actuator endpoint is exposed.

### Audit and logs

Each terminal invocation records one `AI_INVOCATION_OUTCOME` audit event (`GENERATED`, `MODEL_REFUSED`, `GATE_REJECTED` with the exact reason (except authorization/not-found rejections, which are counted and logged but not audited because the customer id is unverified at that point), or `FAILED` with the category), readable with `AUDIT_READ`. Raw prompts, customer notes, phone numbers, API keys and generated message bodies are never logged or audited. The server-generated correlation id appears in logs, the `X-Request-Id` response header and the provider `X-Client-Request-Id` header, which lets support correlate a user report with a log line and an audit row.

## Trust boundaries

Treat these as untrusted input to the model and to the application:

- customer-entered text;
- imported notes;
- provider webhook payloads;
- external website/CRM data;
- generated model output.

Prompt injection can influence model behavior. Therefore prompt wording is never the only security barrier.

## Structured output

Prefer strict schemas and enums over prose parsing.

Avoid designs where a model returns values such as:

```text
tool = "anything"
url = "anything"
template = "anything"
tenant = "anything"
recipient = "anything"
```

and the application executes them directly.

Map model concepts onto application-owned enums and provider configuration.

## Templates and generation

WhatsApp business-initiated messages may require provider-approved templates depending on platform policy and conversation state.

Dokene should distinguish:

- a **Dokene business template**: semantic intent/content definition managed by the application;
- a **provider template**: externally approved channel-specific template identifier;
- a **rendered message**: immutable message content actually approved/sent.

The AI can help select among allowed intents or generate safe variable content, but it should not invent provider template identifiers.

## Human approval

Initial policy:

```text
MANUAL_APPROVAL
```

The operator should see enough context to judge a recommendation:

- customer;
- why follow-up is due;
- relevant purchase/context;
- proposed action;
- proposed message;
- contact/consent state;
- any warnings.

Approval should be explicit and auditable.

## Future auto-send

Automation may evolve toward:

```text
AUTO_SEND_LOW_RISK
```

but only when deterministic policy defines the allowed scenario.

Possible constraints may include:

- approved message category;
- verified consent;
- low contact frequency;
- approved template;
- confidence threshold;
- no recent failure/opt-out;
- tenant explicit opt-in;
- global/tenant kill switch.

The model itself must never toggle these controls.

## Provider abstraction

The application should expose a narrow interface such as:

```text
AiProvider
```

The initial hosted adapter targets OpenAI using the official Java SDK and the Responses API
(`OpenAiResponsesApiAdapter` in `io.github.stevdrey.dokene.ai.provider.openai`), as governed by
[ADR 0016: OpenAI Responses API Adapter with Structured Outputs](../adr/0016-openai-responses-api-adapter-with-structured-outputs.md).
Core follow-up logic depends exclusively on `AiProvider`, never on OpenAI-specific request/response types.

This keeps future options open for other hosted models or local providers.

The provider port takes a typed, already assembled recommendation context and an explicit timeout. It returns
the structured advisory outcome plus safe invocation metadata, or a normalized failure. Each concrete adapter
owns one reusable client, handles provider-specific payloads internally, and preserves cancellation. The
application validates eligibility and authorization before invocation and gates any later action independently
of the provider result.

Configuration is externalized through `dokene.ai.provider` and `dokene.ai.openai` (`api-key`, `model`, `base-url`, `timeout`), with
default model set to `gpt-6-luna`. When `dokene.ai.provider` is unset or empty, Dokene registers `DisabledAiProvider` as a fallback,
ensuring the application boots normally and all deterministic customer, purchase, and follow-up queue/manual workflows remain
fully operational while recommendation requests degrade gracefully to `AI_UNAVAILABLE`. For local development and offline testing,
fake provider mode must be explicitly configured via `dokene.ai.provider=fake`, allowing execution without requiring
an external API key or outbound internet connectivity. In production, setting `dokene.ai.provider=openai` enables the real OpenAI adapter.
Raw prompts, customer text, and API keys are strictly excluded from diagnostic metadata and logs.

## Deterministic rules before AI

Use ordinary application logic when the problem is deterministic.

Examples:

- whether contact consent exists;
- whether the user has `MESSAGE_APPROVE`;
- whether a follow-up date is overdue;
- whether an idempotency key has already been consumed;
- whether a tenant has disabled outbound messages;
- whether a provider integration is enabled.

Use AI for problems such as:

- concise personalized wording;
- contextual recommendation ranking;
- extracting a safe structured summary from complex business context;
- selecting among explicitly allowed semantic actions where heuristics are insufficient.

## Evaluation

AI quality should be evaluated separately from platform correctness.

Useful evaluation dimensions include:

- recommendation relevance;
- draft acceptance/edit rate;
- unsupported/hallucinated action rate;
- schema-valid response rate;
- unsafe recommendation rejection rate;
- latency;
- token/cost consumption;
- provider failure behavior (see [AI Failure Handling, Telemetry and Audit](#ai-failure-handling-telemetry-and-audit) for the operational signals).

A better model is not a reason to weaken deterministic controls.
