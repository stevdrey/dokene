# ADR 0018: Constrained Follow-Up Message Draft Generation

## Status

Accepted

## Context

In Dokene Roadmap Phase 2, the application provides decision-support capabilities to assist operators managing customer relationships. [ADR 0015](0015-structured-next-best-action-recommendation-contracts.md), [ADR 0016](0016-openai-responses-api-adapter-with-structured-outputs.md), and [ADR 0017](0017-deterministic-ai-action-gate.md) established the foundation for next-best-action recommendations, structured OpenAI integration, and deterministic gate evaluation.

Once an operator or system evaluates a due customer, the next logical capability is drafting a personalized follow-up message ready for human review ([Issue #95](https://github.com/stevdrey/dokene/issues/95)). However, automated message drafting presents unique safety and integrity risks:
1. **Fabrication & Hallucination**: Models might hallucinate unapproved discounts (e.g. "50% de descuento"), unverified prices, unauthorized external links, or invent provider-specific template identifiers (e.g. WhatsApp HSM IDs).
2. **Authorization & Trust Boundaries**: Drafting requires explicit authorization (`TenantPermission.MESSAGE_DRAFT`) separate from evaluation (`FOLLOWUP_EVALUATE`).
3. **Data Grounding**: Drafts must be grounded strictly in authoritative customer context, verified purchase history, and tenant business facts (business name and locale). Untrusted customer notes must be treated as passive data and never as instructions.
4. **State Drift**: Customer state or purchase baselines could change concurrently while the model is drafting.
5. **Phase Handoff**: Draft generation belongs to Phase 2 (decision support). Message approval (`MESSAGE_APPROVE`), dispatch state machines, and channel delivery (WhatsApp Cloud API) belong to Phase 3. Draft generation must not prematurely implement sending logic or provider template registries.

## Decision

### 1. Separate Draft Operation and Dedicated Permission

We establish a distinct, dedicated draft generation operation within the AI subsystem:
- Added `AiOperation.MESSAGE_DRAFT` alongside `NEXT_BEST_ACTION`.
- Enforced `TenantPermission.MESSAGE_DRAFT` across service, action gate, and web API layers. Operators must hold both `MESSAGE_DRAFT` and `FOLLOWUP_EVALUATE` to generate drafts.

### 2. Sealed Domain Outcome and Grounded Draft Record

Message draft outcomes are modeled as a sealed domain hierarchy (`io.github.stevdrey.dokene.ai.domain`):

```java
public sealed interface DraftOutcome permits MessageDraft, NoDraft {
    RecommendationConfidence confidence();
    String rationale();
}

public record MessageDraft(
        SemanticAction action,
        SemanticTemplateIntent templateIntent,
        String body,
        DraftVariables draftVariables,
        String locale,
        List<String> evidence,
        List<String> warnings,
        String rationale,
        RecommendationConfidence confidence
) implements DraftOutcome { ... }

public record NoDraft(
        NoDraftReason reason,
        String rationale,
        RecommendationConfidence confidence
) implements DraftOutcome { ... }
```

- **`MessageDraft` Constraints**:
  - `body`: Maximum 1,000 characters, non-blank. Formatted in Latin American Spanish (`es-419`) by default.
  - `locale`: BCP-47 tag (initial default: `es-419`).
  - `evidence`: Up to 10 factual citations grounded in customer history (e.g. `"Compra reciente: Café Molido"`).
  - `warnings`: Up to 10 advisory warnings for the operator.
  - `draftVariables`: Closed key-value bag for template placeholder interpolation.
- **`NoDraft` Reasons**:
  - Closed enum `NoDraftReason`: `INSUFFICIENT_HISTORY`, `UNSUPPORTED_ACTION`, `MISSING_TRUSTED_FACTS`, `SAFETY_VIOLATION`, `MANUAL_REVIEW_REQUIRED`.

### 3. Draft Context and Trusted Business Facts

Draft generation requires a structured `DraftContext` (`io.github.stevdrey.dokene.ai.application`):
- Combines `RecommendationContext` (trusted customer facts and sanitized untrusted text), `SemanticAction`, `SemanticTemplateIntent`, and `TrustedBusinessFacts`.
- `TrustedBusinessFacts` supplies the authoritative tenant business name and preferred locale directly from the tenant record, preventing the model from inventing business identities.

### 4. Zero-Fabrication Safety Validator

Deterministic validation is enforced by `DraftSafetyValidator` before accepting any model draft:
- **Prohibited URLs/Links**: Rejects any draft body containing `http://`, `https://`, `ftp://`, `www.`, or URL-like domain references.
- **Prohibited Provider Template IDs**: Rejects provider-specific template identifiers, such as WhatsApp/Meta template namespace strings (`template_id`, `hsm_id`, `waba_`).
- **Hallucinated Discounts & Offers**: Rejects terms like `descuento`, `rebaja`, `oferta`, `cupón`, `gratis`, `%`, `$`, `USD`, `CRC` unless explicitly present in the grounding context (e.g., in customer purchase descriptions or notes).

### 5. Strict Structured Outputs via JSON Schema

For provider implementations (such as OpenAI Responses API in `OpenAiResponsesApiAdapter`), draft outputs are constrained with strict JSON Schema (`DraftJsonSchema`):
- Root object `draft` with `outcome` discriminator (`DRAFT` vs `NO_DRAFT`).
- `additionalProperties: false` on all objects.
- System prompt instructs the model to draft concise Latin American Spanish messages grounded only in provided context.

### 6. Action Gate Integration

`AiActionGate` provides `evaluateDraft(CustomerId, Assembly, DraftOutcome)` and `revalidateDraftAuthorization(CustomerId)`:
- Revalidates tenant status, caller membership, `MESSAGE_DRAFT` and `FOLLOWUP_EVALUATE` permissions, customer existence, active state, contact policy (consent and opt-out), and baseline freshness.
- Runs `DraftSafetyValidator` against the generated draft body.
- Returns a sealed `DraftGateDecision` (`Accepted` or `Rejected` with typed `ActionGateRejectionReason`).
- Emits durable security audit events with `TenantPermission.MESSAGE_DRAFT` on rejections.

### 7. Orchestration and Web API

Orchestration is managed by `FollowUpDraftService` and exposed via REST:
- `POST /api/customers/{customerId}/draft` (with alias `POST /api/customers/{customerId}/follow-up-draft`).
- Supports optimistic concurrency with `If-Match: "<version>"` and response `ETag: "<version>"`.
- Produces normalized `FollowUpDraftResult` with statuses: `AVAILABLE`, `NO_DRAFT`, `INELIGIBLE`, `STALE_STATE`, `AI_UNAVAILABLE`.
- Gracefully degrades to `AI_UNAVAILABLE` when the AI provider is disabled, times out, or fails.

### 8. Phase 2 to Phase 3 Transition

This ADR explicitly scopes draft generation to decision support:
- Drafts are proposed messages presented to human operators in the UI.
- No WhatsApp or messaging provider API is invoked.
- No message dispatch state machine is executed.
- In Phase 3, an approved draft will be submitted through an operator approval flow (`TenantPermission.MESSAGE_APPROVE`) and dispatched via an outbound messaging port with provider-specific template mappings and delivery tracking.

## Consequences

### Positive
- Strict confinement: zero hallucinated discounts, external URLs, or synthetic template IDs.
- Deterministic safety gate ensures untrusted model outputs cannot reach operators if customer state drifted or safety rules are violated.
- Provider-neutral domain contracts decouple core business logic from LLM SDKs.
- Clean architectural boundary preparing for Phase 3 message approval and dispatch workflows.

### Negative / Trade-offs
- Strict safety rules may reject creative marketing wording, which is acceptable given Dokene's emphasis on trusted, non-hallucinatory business communications.
- Generating both a recommendation and a subsequent draft requires two model round-trips if performed sequentially; however, rate limiting and separate permissions maintain operational safety and cost control.
