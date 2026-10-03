# ADR 0017: Deterministic AI Action Gate for Recommendations and Drafts

## Status

Accepted

## Context

Under [ADR 0004: AI Action Gate](0004-ai-action-gate.md), model output is strictly advisory, untrusted, and may never directly trigger an external side effect. [ADR 0015: Structured Next Best Action Recommendation Contracts](0015-structured-next-best-action-recommendation-contracts.md) established closed-vocabulary recommendation domain contracts, and [ADR 0016: OpenAI Responses API Adapter with Structured Outputs](0016-openai-responses-api-adapter-with-structured-outputs.md) introduced provider-neutral structured execution.

In Roadmap Phase 2, language models generate next-best-action suggestions and draft message parameters. However:
1. Model outputs are generated asynchronously or over network calls, during which customer state, consent, or purchase history may have changed (state drift / staleness).
2. Malicious or compromised prompt injections or corrupted model generations could attempt to recommend actions for archived customers, customers who opted out (DNC) or revoked consent, ineligible follow-up states, or unapproved actions and message template intents.
3. Model fields must never override the tenant, customer, or eligibility authority of the application core.

To operationalize ADR 0004 for Phase 2 recommendations, Dokene requires a deterministic `AiActionGate` that revalidates every recommendation against current authoritative Dokene state before accepting or exposing it as an actionable suggestion.

## Decision

### 1. Revalidation Against Authoritative State

The `AiActionGate` evaluates every `RecommendationOutcome` against fresh, authoritative state at gate evaluation time:

1. **Tenant Context**: Revalidates an active, authenticated `TenantContext`. Missing tenant context fails closed with `NO_TENANT_CONTEXT`.
2. **Caller Authorization**: Verifies the calling actor holds `TenantPermission.FOLLOWUP_EVALUATE`. Unauthorized callers fail closed with `UNAUTHORIZED`.
3. **Resource Ownership & Existence**: Fetches the customer record directly from the authoritative customer repository within the active tenant. Non-existent customers fail closed with `CUSTOMER_NOT_FOUND`.
4. **Customer Status**: Revalidates customer active status. Archived customers fail closed with `CUSTOMER_ARCHIVED`.
5. **Contact Policy & Consent**: Revalidates current contact policy and WhatsApp channel consent via `ContactPolicyService`. Customers marked `doNotContact` fail with `DO_NOT_CONTACT`; customers lacking granted WhatsApp consent fail with `NO_CONTACT_CONSENT`.
6. **Fresh Follow-Up Eligibility**: Evaluates follow-up status using fresh authoritative purchase history and tenant cadence via `FollowUpEvaluationService`.
7. **Stale State Detection**: Compares the assembly baseline (`lastPurchaseId`, `lastPurchaseTime`, `effectiveCadenceDays`) against the current authoritative state. If the baseline does not match current state, the recommendation is rejected with `STALE_STATE`.
8. **Follow-Up Ineligible**: If the customer is not currently `DUE` or `OVERDUE` (e.g., `NOT_YET_DUE` or `INELIGIBLE`), action recommendations are rejected with `FOLLOW_UP_INELIGIBLE`.
9. **Semantic Action & Template Intent Allowlists**: Revalidates that the recommended `SemanticAction` is in the allowed action set for the current status, and that the `SemanticTemplateIntent` is compatible with the action. Unknown or incompatible combinations fail closed with `DISALLOWED_ACTION` or `DISALLOWED_TEMPLATE_INTENT`.

### 2. Sealed Decision Model and Typed Rejections

The evaluation produces a sealed `ActionGateDecision`:

```java
public sealed interface ActionGateDecision permits ActionGateDecision.Accepted, ActionGateDecision.Rejected {
    boolean isAccepted();
    default boolean isRejected() { return !isAccepted(); }
    Optional<ActionGateRejectionReason> rejectionReason();
    Optional<String> diagnostic();
    FollowUpEvaluation currentEvaluation();
    default Optional<FollowUpEvaluation> evaluation() { return Optional.ofNullable(currentEvaluation()); }
    default Optional<RecommendationOutcome> rawOutcome() { return Optional.empty(); }

    record Accepted(RecommendationOutcome outcome, FollowUpEvaluation currentEvaluation) implements ActionGateDecision { ... }
    record Rejected(ActionGateRejectionReason reason, String diagnosticMessage, FollowUpEvaluation currentEvaluation, RecommendationOutcome rawOutcome) implements ActionGateDecision { ... }
}
```

The rejection reasons are defined as a closed enum (`ActionGateRejectionReason`):
- `NO_TENANT_CONTEXT`
- `UNAUTHORIZED`
- `CUSTOMER_NOT_FOUND`
- `CUSTOMER_ARCHIVED`
- `DO_NOT_CONTACT`
- `NO_CONTACT_CONSENT`
- `FOLLOW_UP_INELIGIBLE`
- `STALE_STATE`
- `DISALLOWED_ACTION`
- `DISALLOWED_TEMPLATE_INTENT`
- `INVALID_RECOMMENDATION`

Explicit refusals (`NoRecommendation`) returned by the model pass through the gate without requiring action eligibility checks, provided tenant context, authorization, and customer status checks pass.

On rejection, `Rejected` retains the raw unaccepted `RecommendationOutcome` alongside the fresh authoritative `FollowUpEvaluation` and typed rejection reason. This raw outcome is available strictly for offline analytics, troubleshooting, and audit telemetry via `decision.rawOutcome()`, but is never exposed as an actionable suggestion.

### 3. Separation of Authority in `FollowUpDecision`

`FollowUpDecision` composes:
- `FollowUpEvaluation evaluation`: Deterministic, authoritative evaluation facts.
- `RecommendationOutcome recommendation`: Gate-approved outcome (must match `accepted.outcome()` when accepted, or `null` when rejected).
- `ActionGateDecision gateDecision`: Authoritative verification decision from `AiActionGate`.

When `gateDecision.isRejected()`, `hasActionRecommendation()` returns `false` and `advisoryRecommendation()` returns `Optional.empty()`, ensuring that downstream operator views and automated handlers can never consume or display an unauthorized or stale recommendation as actionable. The raw rejected outcome remains accessible solely via `decision.rawOutcome()` for non-actionable diagnostics.

### 4. Zero External Side Effects

Gate evaluation is strictly read-only validation:
- No outbound messages are dispatched.
- No customer, purchase, or follow-up disposition mutations occur.
- Read operations are protected by tenant context and repository boundaries.

### 5. Privacy-Safe Durable Security Auditing

Security-relevant rejections are emitted to `AiActionGateAuditListener`:
- Emits typed `SecurityRejectionEvent` carrying tenant ID, customer ID, rejection reason, caller actor ID, and evaluation timestamp.
- Strictly excludes raw prompt text, customer notes, purchase descriptions, draft variables, or sensitive PII.
- Handled durably in production by `DurableAiActionGateAuditListener`, which maps rejection reasons to `AuditDenialReason` and records them through `AuditRecorder.authorizationDenied(TenantPermission.FOLLOWUP_EVALUATE, denialReason)` into the append-only `audit_events` table under `REQUIRES_NEW` transaction propagation, fulfilling Security Invariant 12.

This mapping is intentionally lossy (most reasons collapse to `INSUFFICIENT_PERMISSION`). [ADR 0019](0019-ai-failure-handling-telemetry-and-audit.md) adds a complementary `AI_INVOCATION_OUTCOME` audit event that records the exact `ActionGateRejectionReason` as `GATE_REJECTED`, alongside `GENERATED`, `MODEL_REFUSED` and `FAILED` outcomes, plus a `dokene.ai.gate.rejections` metric.

## Consequences

- **Inviolable Invariant**: AI models remain untrusted and advisory. Model output cannot bypass tenant boundary, customer archive status, DNC opt-out, missing consent, or follow-up eligibility.
- **Race Condition Immunity**: Concurrently recorded purchases, cadence adjustments, or consent revocations between context assembly and operator review are reliably intercepted as `STALE_STATE`, `NO_CONTACT_CONSENT`, or `DO_NOT_CONTACT`.
- **Operator Safety**: Operators are protected from seeing misleading or invalid AI recommendations when underlying customer facts have changed.
- **Fail Closed**: Any security violation, missing context, or malformed state cleanly rejects the recommendation with structured reason codes rather than unhandled runtime exceptions.
